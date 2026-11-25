/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.zooinspector.dev;

import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;

import com.zooinspector.core.ConnectProfile;
import com.zooinspector.core.Inspector;
import com.zooinspector.core.NodeSnapshot;
import com.zooinspector.core.ZkConnection;

/** 写操作与异常路径检查：只连本地 DevServer */
public final class OpsCheck
{
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static int failures;

	public static void main(String[] args) throws Exception
	{
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		System.setProperty("zookeeper.sasl.client", "false");
		String hosts = args.length > 0 ? args[0] : "127.0.0.1:21811";

		Inspector inspector = new Inspector();
		ConnectProfile p = new ConnectProfile();
		p.hosts = hosts;
		check("connect", connect(inspector, p) == null);
		waitFor(new BooleanSupplier()
		{
			@Override
			public boolean getAsBoolean()
			{
				return inspector.index().count > 0 && !inspector.isIndexing();
			}
		}, 120000, "index");
		int before = inspector.index().count;
		System.out.println("index before = " + before);

		ZkConnection conn = inspector.connection();
		// 清掉上次遗留
		try
		{
			conn.deleteRecursive("/ops-test", new java.util.concurrent.atomic.AtomicBoolean(false));
		}
		catch (Exception e)
		{
			// 不存在则忽略
		}
		conn.create("/ops-test", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

		// 1. 新建 + 索引同步
		String err = create(inspector, "/ops-test", "child-a", "{\"a\":1}".getBytes(UTF8),
				CreateMode.PERSISTENT);
		check("create persistent", err == null);
		check("index picked up new node", inspector.index().contains("/ops-test/child-a"));

		err = create(inspector, "/ops-test", "seq-", "x".getBytes(UTF8),
				CreateMode.PERSISTENT_SEQUENTIAL);
		check("create sequential", err == null);
		List<String> kids = conn.children("/ops-test").names;
		check("sequential child exists", kids.size() == 2);

		// 树只对已加载的父节点就地打补丁。/ops-test 是绕过应用直接建的，
		// 先刷新根让树看到它，再加载它，然后验证新建能就地打补丁
		inspector.tree().reload(inspector.tree().root());
		check("tree refresh sees external node", waitFor(new BooleanSupplier()
		{
			@Override
			public boolean getAsBoolean()
			{
				return inspector.tree().nodeFor("/ops-test") != null;
			}
		}, 20000));
		final CountDownLatch treeLoaded = new CountDownLatch(1);
		com.zooinspector.core.ZkTreeModel.Node opsNode = inspector.tree().nodeFor("/ops-test");
		if (opsNode != null)
		{
			inspector.tree().ensureLoaded(opsNode, new Runnable()
			{
				@Override
				public void run()
				{
					treeLoaded.countDown();
				}
			});
		}
		check("tree loaded parent", treeLoaded.await(30, TimeUnit.SECONDS));
		err = create(inspector, "/ops-test", "child-b", new byte[0], CreateMode.PERSISTENT);
		check("create second child", err == null);
		check("tree patched for loaded parent", inspector.tree().nodeFor("/ops-test/child-b") != null);

		// 2. 重复创建应给出明确错误
		err = create(inspector, "/ops-test", "child-a", new byte[0], CreateMode.PERSISTENT);
		check("duplicate create rejected", "That node already exists.".equals(err));

		// 3. 改数据 + 版本冲突
		NodeSnapshot snap = conn.exists("/ops-test/child-a");
		err = saveData(inspector, "/ops-test/child-a", "{\"a\":2}".getBytes(UTF8), snap.version);
		check("save data", err == null);
		check("data written", new String(conn.data("/ops-test/child-a").bytes, UTF8)
				.equals("{\"a\":2}"));
		err = saveData(inspector, "/ops-test/child-a", "{\"a\":3}".getBytes(UTF8), snap.version);
		check("stale version rejected", err != null && err.contains("version conflict"));

		// 4. ACL 读写
		List<ACL> readOnly = new java.util.ArrayList<ACL>();
		readOnly.add(new ACL(ZooDefs.Perms.READ | ZooDefs.Perms.ADMIN, new Id("world", "anyone")));
		NodeSnapshot s2 = conn.exists("/ops-test/child-a");
		err = saveAcl(inspector, "/ops-test/child-a", readOnly, s2.aversion);
		check("set acl", err == null);
		List<ACL> readBack = conn.acl("/ops-test/child-a");
		check("acl read back", readBack.size() == 1
				&& readBack.get(0).getPerms() == (ZooDefs.Perms.READ | ZooDefs.Perms.ADMIN));

		// 5. 递归删除 + 索引同步
		conn.create("/ops-test/deep", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		conn.create("/ops-test/deep/leaf", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
				CreateMode.PERSISTENT);
		inspector.reindexSubtree("/ops-test");
		Thread.sleep(800);
		check("subtree reindex saw deep leaf", inspector.index().contains("/ops-test/deep/leaf"));
		err = delete(inspector, "/ops-test");
		check("recursive delete", err == null);
		check("node gone", conn.exists("/ops-test") == null);
		check("index dropped subtree", !inspector.index().contains("/ops-test/child-a")
				&& !inspector.index().contains("/ops-test"));

		// 6. 根节点不允许删
		err = delete(inspector, "/");
		check("root delete refused", err != null);

		// 7. 不存在的节点
		String missing = null;
		try
		{
			conn.data("/definitely-not-here-12345");
			missing = "no error";
		}
		catch (Exception e)
		{
			missing = Inspector.message(e);
		}
		check("missing node message", "Node no longer exists.".equals(missing));

		// 8. 连不上的地址要给出可读原因
		Inspector bad = new Inspector();
		ConnectProfile bp = new ConnectProfile();
		bp.hosts = "127.0.0.1:1";
		bp.connectTimeoutMs = 2500;
		String badErr = connect(bad, bp);
		check("unreachable host message", badErr != null && badErr.contains("Timed out"));
		System.out.println("  unreachable -> " + badErr);

		inspector.disconnect(null);
		System.out.println(failures == 0 ? "OPS_CHECK ALL PASSED" : "OPS_CHECK FAILURES=" + failures);
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void check(String what, boolean ok)
	{
		System.out.println((ok ? "PASS " : "FAIL ") + what);
		if (!ok)
		{
			failures++;
		}
	}

	private static String connect(Inspector inspector, ConnectProfile p) throws Exception
	{
		return await(inspector, new Action()
		{
			@Override
			public void run(Inspector.Done done)
			{
				inspector.connect(p, done);
			}
		});
	}

	private static String create(Inspector inspector, final String parent, final String name,
			final byte[] data, final CreateMode mode) throws Exception
	{
		return await(inspector, new Action()
		{
			@Override
			public void run(Inspector.Done done)
			{
				inspector.createNode(parent, name, data, mode, null, done);
			}
		});
	}

	private static String saveData(Inspector inspector, final String path, final byte[] data,
			final int version) throws Exception
	{
		return await(inspector, new Action()
		{
			@Override
			public void run(Inspector.Done done)
			{
				inspector.saveData(path, data, version, done);
			}
		});
	}

	private static String saveAcl(Inspector inspector, final String path, final List<ACL> acls,
			final int aversion) throws Exception
	{
		return await(inspector, new Action()
		{
			@Override
			public void run(Inspector.Done done)
			{
				inspector.saveAcl(path, acls, aversion, done);
			}
		});
	}

	private static String delete(Inspector inspector, final String path) throws Exception
	{
		return await(inspector, new Action()
		{
			@Override
			public void run(Inspector.Done done)
			{
				inspector.deleteRecursive(path, done);
			}
		});
	}

	private interface Action
	{
		void run(Inspector.Done done);
	}

	private static String await(Inspector inspector, Action action) throws Exception
	{
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicReference<String> error = new AtomicReference<String>();
		action.run(new Inspector.Done()
		{
			@Override
			public void done(String e)
			{
				error.set(e);
				latch.countDown();
			}
		});
		if (!latch.await(60, TimeUnit.SECONDS))
		{
			throw new IllegalStateException("operation timed out");
		}
		return error.get();
	}

	private static boolean waitFor(BooleanSupplier condition, long timeoutMs)
			throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline)
		{
			if (condition.getAsBoolean())
			{
				return true;
			}
			Thread.sleep(100);
		}
		return false;
	}

	private static void waitFor(BooleanSupplier condition, long timeoutMs, String what)
			throws InterruptedException
	{
		if (!waitFor(condition, timeoutMs))
		{
			throw new IllegalStateException("timeout waiting for " + what);
		}
	}

	private OpsCheck()
	{
	}
}