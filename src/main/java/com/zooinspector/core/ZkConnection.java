/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zooinspector.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import com.zooinspector.util.ZkPaths;

/**
 * ZooKeeper 连接封装。
 *
 * <p>约定：本类所有方法都只能在后台线程调用（ZooKeeper 客户端本身线程安全，
 * 但同步调用会阻塞，绝不能放在 EDT 上）。
 */
public class ZkConnection implements Watcher
{
	public enum State
	{
		DISCONNECTED, CONNECTING, CONNECTED, READ_ONLY, LOST, EXPIRED
	}

	public interface StateListener
	{
		void stateChanged(State state, String detail);
	}

	/** 一次 getChildren 同时拿到子节点与 stat，省掉老版的第二次 exists 往返 */
	public static final class Children
	{
		public final List<String> names;
		public final NodeSnapshot snapshot;

		public Children(List<String> names, NodeSnapshot snapshot)
		{
			this.names = names;
			this.snapshot = snapshot;
		}
	}

	public static final class Data
	{
		public final byte[] bytes;
		public final NodeSnapshot snapshot;

		public Data(byte[] bytes, NodeSnapshot snapshot)
		{
			this.bytes = bytes == null ? new byte[0] : bytes;
			this.snapshot = snapshot;
		}
	}

	private volatile ZooKeeper zk;
	private volatile ConnectProfile profile;
	private volatile State state = State.DISCONNECTED;
	private volatile CountDownLatch connectLatch;
	private final List<StateListener> listeners = new CopyOnWriteArrayList<StateListener>();

	public void addStateListener(StateListener l)
	{
		listeners.add(l);
	}

	public State state()
	{
		return state;
	}

	public ConnectProfile profile()
	{
		return profile;
	}

	public boolean isUsable()
	{
		ZooKeeper client = zk;
		return client != null && client.getState().isAlive()
				&& state != State.EXPIRED;
	}

	public long sessionId()
	{
		ZooKeeper client = zk;
		return client == null ? 0L : client.getSessionId();
	}

	/** 建立连接并等到 SyncConnected；超时或失败抛出带明确原因的异常 */
	public synchronized void connect(ConnectProfile p) throws IOException, InterruptedException,
			KeeperException
	{
		closeQuietly();
		if (p.hosts == null || p.hosts.trim().isEmpty())
		{
			throw new IOException("Connect string is required.");
		}
		setState(State.CONNECTING, p.hosts);
		CountDownLatch latch = new CountDownLatch(1);
		this.connectLatch = latch;
		this.profile = p;

		ZooKeeper client = new ZooKeeper(p.hosts.trim(), Math.max(1000, p.sessionTimeoutMs), this);
		this.zk = client;
		for (ConnectProfile.Auth a : p.auths)
		{
			if (!a.isEmpty())
			{
				String scheme = a.scheme == null || a.scheme.isEmpty() ? "digest" : a.scheme;
				client.addAuthInfo(scheme, (a.id + ":" + a.secret).getBytes("UTF-8"));
			}
		}

		int timeout = Math.max(1000, p.connectTimeoutMs);
		if (!latch.await(timeout, TimeUnit.MILLISECONDS))
		{
			closeQuietly();
			setState(State.DISCONNECTED, "timeout");
			throw new IOException("Timed out after " + timeout + " ms connecting to " + p.hosts
					+ ". Check the address, port and firewall.");
		}
		if (state == State.EXPIRED)
		{
			closeQuietly();
			throw new IOException("Session could not be established (authentication failed?).");
		}
		// 真正打一次请求，确认 chroot 存在且可读
		try
		{
			client.exists("/", false);
		}
		catch (KeeperException.NoNodeException e)
		{
			closeQuietly();
			setState(State.DISCONNECTED, "chroot missing");
			throw new IOException("Connected, but the chroot path in '" + p.hosts
					+ "' does not exist on the server.", e);
		}
	}

	public synchronized void close()
	{
		closeQuietly();
		setState(State.DISCONNECTED, null);
	}

	private void closeQuietly()
	{
		ZooKeeper client = this.zk;
		this.zk = null;
		if (client != null)
		{
			try
			{
				client.close();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			catch (RuntimeException e)
			{
				// 关闭失败不影响后续重连
			}
		}
	}

	@Override
	public void process(WatchedEvent event)
	{
		if (event.getType() != Event.EventType.None)
		{
			// 节点级事件由各自的 watcher 处理
			return;
		}
		CountDownLatch latch = connectLatch;
		switch (event.getState())
		{
			case SyncConnected:
				setState(State.CONNECTED, null);
				if (latch != null)
				{
					latch.countDown();
				}
				break;
			case ConnectedReadOnly:
				setState(State.READ_ONLY, null);
				if (latch != null)
				{
					latch.countDown();
				}
				break;
			case Disconnected:
				setState(State.LOST, "connection lost, client is retrying");
				break;
			case Expired:
				setState(State.EXPIRED, "session expired");
				if (latch != null)
				{
					latch.countDown();
				}
				break;
			case AuthFailed:
				setState(State.EXPIRED, "authentication failed");
				if (latch != null)
				{
					latch.countDown();
				}
				break;
			default:
				break;
		}
	}

	private void setState(State s, String detail)
	{
		this.state = s;
		for (StateListener l : listeners)
		{
			l.stateChanged(s, detail);
		}
	}

	private ZooKeeper require()
	{
		ZooKeeper client = zk;
		if (client == null)
		{
			throw new IllegalStateException("Not connected.");
		}
		return client;
	}

	private interface Op<T>
	{
		T run(ZooKeeper client) throws KeeperException, InterruptedException;
	}

	/** 读操作对瞬时断连做有限重试；写操作不重试，避免重复创建 */
	private <T> T read(Op<T> op) throws KeeperException, InterruptedException
	{
		KeeperException last = null;
		for (int attempt = 0; attempt < 3; attempt++)
		{
			try
			{
				return op.run(require());
			}
			catch (KeeperException.ConnectionLossException e)
			{
				last = e;
			}
			catch (KeeperException.OperationTimeoutException e)
			{
				last = e;
			}
			if (attempt < 2)
			{
				Thread.sleep(150L * (attempt + 1));
			}
		}
		throw last;
	}

	public Children children(final String path) throws KeeperException, InterruptedException
	{
		final String p = ZkPaths.normalize(path);
		final Stat stat = new Stat();
		List<String> names = read(new Op<List<String>>()
		{
			@Override
			public List<String> run(ZooKeeper client) throws KeeperException, InterruptedException
			{
				return client.getChildren(p, false, stat);
			}
		});
		return new Children(ZkPaths.sorted(names), new NodeSnapshot(p, stat));
	}

	/** 带 watch 的版本，用于展开节点的变更通知 */
	public Children childrenWatched(final String path, final Watcher watcher)
			throws KeeperException, InterruptedException
	{
		final String p = ZkPaths.normalize(path);
		final Stat stat = new Stat();
		List<String> names = read(new Op<List<String>>()
		{
			@Override
			public List<String> run(ZooKeeper client) throws KeeperException, InterruptedException
			{
				return client.getChildren(p, watcher, stat);
			}
		});
		return new Children(ZkPaths.sorted(names), new NodeSnapshot(p, stat));
	}

	public Data data(final String path) throws KeeperException, InterruptedException
	{
		final String p = ZkPaths.normalize(path);
		final Stat stat = new Stat();
		byte[] bytes = read(new Op<byte[]>()
		{
			@Override
			public byte[] run(ZooKeeper client) throws KeeperException, InterruptedException
			{
				return client.getData(p, false, stat);
			}
		});
		return new Data(bytes, new NodeSnapshot(p, stat));
	}

	public NodeSnapshot exists(final String path) throws KeeperException, InterruptedException
	{
		final String p = ZkPaths.normalize(path);
		Stat stat = read(new Op<Stat>()
		{
			@Override
			public Stat run(ZooKeeper client) throws KeeperException, InterruptedException
			{
				return client.exists(p, false);
			}
		});
		return stat == null ? null : new NodeSnapshot(p, stat);
	}

	public List<ACL> acl(final String path) throws KeeperException, InterruptedException
	{
		final String p = ZkPaths.normalize(path);
		return read(new Op<List<ACL>>()
		{
			@Override
			public List<ACL> run(ZooKeeper client) throws KeeperException, InterruptedException
			{
				return client.getACL(p, new Stat());
			}
		});
	}

	public void setAcl(final String path, final List<ACL> acls, final int aversion)
			throws KeeperException, InterruptedException
	{
		final String p = ZkPaths.normalize(path);
		require().setACL(p, acls, aversion);
	}

	public String create(final String path, final byte[] data, final List<ACL> acls,
			final CreateMode mode) throws KeeperException, InterruptedException
	{
		final String p = ZkPaths.normalize(path);
		return require().create(p, data, acls, mode);
	}

	public void setData(final String path, final byte[] data, final int version)
			throws KeeperException, InterruptedException
	{
		final String p = ZkPaths.normalize(path);
		require().setData(p, data, version);
	}

	public void delete(final String path, final int version) throws KeeperException,
			InterruptedException
	{
		final String p = ZkPaths.normalize(path);
		require().delete(p, version);
	}

	/**
	 * 递归删除。用显式栈避免深树递归爆栈；返回实际删除的节点数。
	 * cancel 置位后停止，已删除的部分不回滚（ZooKeeper 无事务回滚）。
	 */
	public int deleteRecursive(String path, AtomicBoolean cancel) throws KeeperException,
			InterruptedException
	{
		String root = ZkPaths.normalize(path);
		if (ZkPaths.ROOT.equals(root))
		{
			throw new KeeperException.BadArgumentsException("Refusing to delete the root node.");
		}
		// 后序遍历：先把所有后代压栈，再从最深处往回删
		Deque<String> order = new ArrayDeque<String>();
		Deque<String> pending = new ArrayDeque<String>();
		pending.push(root);
		while (!pending.isEmpty())
		{
			if (cancel != null && cancel.get())
			{
				break;
			}
			String current = pending.pop();
			order.push(current);
			List<String> names;
			try
			{
				names = require().getChildren(current, false);
			}
			catch (KeeperException.NoNodeException e)
			{
				continue;
			}
			for (String n : names)
			{
				pending.push(ZkPaths.join(current, n));
			}
		}
		AtomicInteger deleted = new AtomicInteger();
		for (String p : order)
		{
			if (cancel != null && cancel.get())
			{
				break;
			}
			try
			{
				require().delete(p, -1);
				deleted.incrementAndGet();
			}
			catch (KeeperException.NoNodeException e)
			{
				// 已经被别人删掉了，跳过
			}
			catch (KeeperException.NotEmptyException e)
			{
				// 遍历期间新增了子节点，再递归一次
				deleted.addAndGet(deleteRecursive(p, cancel));
			}
		}
		return deleted.get();
	}

	/** 统计子树节点数（含自身），用于删除确认提示 */
	public int countSubtree(String path, int cap) throws KeeperException, InterruptedException
	{
		Deque<String> pending = new ArrayDeque<String>();
		pending.push(ZkPaths.normalize(path));
		int count = 0;
		while (!pending.isEmpty() && count < cap)
		{
			String current = pending.pop();
			count++;
			List<String> names;
			try
			{
				names = require().getChildren(current, false);
			}
			catch (KeeperException.NoNodeException e)
			{
				continue;
			}
			for (String n : names)
			{
				pending.push(ZkPaths.join(current, n));
			}
		}
		return count;
	}

	// ------------------------------------------------------------------
	// 异步 API：ZooKeeper 客户端会把异步请求在同一连接上流水线化，
	// 一次往返就能带回大批结果，比多线程同步调用更快、对集群也更温和。
	// 回调在 ZooKeeper 的 EventThread 上串行执行，实现里绝不能阻塞。
	// 约定：返回 false 表示请求根本没发出去（未连接），此时不会触发回调。
	// ------------------------------------------------------------------

	public interface ChildrenCallback
	{
		void done(String path, List<String> names, NodeSnapshot snapshot, Throwable error);
	}

	public interface SnapshotCallback
	{
		void done(String path, NodeSnapshot snapshot, Throwable error);
	}

	public interface DataCallback
	{
		void done(String path, byte[] bytes, NodeSnapshot snapshot, Throwable error);
	}

	public boolean getChildrenAsync(final String path, final ChildrenCallback callback)
	{
		ZooKeeper client = zk;
		if (client == null)
		{
			return false;
		}
		final String p = ZkPaths.normalize(path);
		try
		{
			client.getChildren(p, false, new AsyncCallback.Children2Callback()
			{
				@Override
				public void processResult(int rc, String pathArg, Object ctx,
						List<String> children, Stat stat)
				{
					if (rc == KeeperException.Code.OK.intValue())
					{
						callback.done(p, ZkPaths.sorted(children), new NodeSnapshot(p, stat), null);
					}
					else
					{
						callback.done(p, null, null, KeeperException.create(
								KeeperException.Code.get(rc), p));
					}
				}
			}, null);
			return true;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	public boolean existsAsync(final String path, final SnapshotCallback callback)
	{
		ZooKeeper client = zk;
		if (client == null)
		{
			return false;
		}
		final String p = ZkPaths.normalize(path);
		try
		{
			client.exists(p, false, new AsyncCallback.StatCallback()
			{
				@Override
				public void processResult(int rc, String pathArg, Object ctx, Stat stat)
				{
					if (rc == KeeperException.Code.OK.intValue())
					{
						callback.done(p, new NodeSnapshot(p, stat), null);
					}
					else if (rc == KeeperException.Code.NONODE.intValue())
					{
						callback.done(p, null, null);
					}
					else
					{
						callback.done(p, null, KeeperException.create(KeeperException.Code.get(rc),
								p));
					}
				}
			}, null);
			return true;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	public boolean getDataAsync(final String path, final DataCallback callback)
	{
		ZooKeeper client = zk;
		if (client == null)
		{
			return false;
		}
		final String p = ZkPaths.normalize(path);
		try
		{
			client.getData(p, false, new AsyncCallback.DataCallback()
			{
				@Override
				public void processResult(int rc, String pathArg, Object ctx, byte[] data, Stat stat)
				{
					if (rc == KeeperException.Code.OK.intValue())
					{
						callback.done(p, data, new NodeSnapshot(p, stat), null);
					}
					else
					{
						callback.done(p, null, null, KeeperException.create(
								KeeperException.Code.get(rc), p));
					}
				}
			}, null);
			return true;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	/** 供 ACL 面板等处使用的空列表副本 */
	public static List<ACL> copyAcls(List<ACL> source)
	{
		List<ACL> out = new ArrayList<ACL>();
		for (ACL a : source)
		{
			out.add(new ACL(a.getPerms(), new org.apache.zookeeper.data.Id(a.getId().getScheme(), a
					.getId().getId())));
		}
		return out;
	}
}