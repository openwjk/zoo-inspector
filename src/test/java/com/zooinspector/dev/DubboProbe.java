/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.zooinspector.dev;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import com.zooinspector.core.ConnectProfile;
import com.zooinspector.core.Dubbo;
import com.zooinspector.core.IndexBuilder;
import com.zooinspector.core.PathIndex;
import com.zooinspector.core.ZkConnection;

/** 造一棵 Dubbo 形状的树，看当前搜索到底返回什么 */
public final class DubboProbe
{
	private static final Charset UTF8 = Charset.forName("UTF-8");

	public static void main(String[] args) throws Exception
	{
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		System.setProperty("zookeeper.sasl.client", "false");
		String hosts = args.length > 0 ? args[0] : "127.0.0.1:21811";

		final CountDownLatch up = new CountDownLatch(1);
		ZooKeeper zk = new ZooKeeper(hosts, 60000, new Watcher()
		{
			@Override
			public void process(WatchedEvent e)
			{
				if (e.getState() == Event.KeeperState.SyncConnected)
				{
					up.countDown();
				}
			}
		});
		if (!up.await(20, TimeUnit.SECONDS))
		{
			throw new IllegalStateException("no connect");
		}
		mkdir(zk, "/dubbo");
		// 40 个接口，每个 120 个 provider + 60 个 consumer
		for (int i = 0; i < 40; i++)
		{
			String iface = "com.example.trade.Order" + i + "Service";
			String base = "/dubbo/" + iface;
			mkdir(zk, base);
			mkdir(zk, base + "/providers");
			mkdir(zk, base + "/consumers");
			mkdir(zk, base + "/configurators");
			mkdir(zk, base + "/routers");
			for (int p = 0; p < 120; p++)
			{
				String url = "dubbo://10.30." + (i % 8) + "." + (p + 1) + ":20880/" + iface
						+ "?application=order-app&side=provider&version=1.0." + p;
				mkdir(zk, base + "/providers/" + URLEncoder.encode(url, "UTF-8"));
			}
			for (int c = 0; c < 60; c++)
			{
				String url = "consumer://10.40." + (i % 8) + "." + (c + 1) + "/" + iface
						+ "?application=caller-" + c + "&side=consumer";
				mkdir(zk, base + "/consumers/" + URLEncoder.encode(url, "UTF-8"));
			}
		}
		zk.close();

		ConnectProfile p = new ConnectProfile();
		p.hosts = hosts;
		p.sessionTimeoutMs = 60000;
		ZkConnection conn = new ZkConnection();
		conn.connect(p);
		final PathIndex[] holder = new PathIndex[1];
		final CountDownLatch done = new CountDownLatch(1);
		IndexBuilder.Options opts = new IndexBuilder.Options();
		opts.maxNodes = 500000;
		new IndexBuilder(conn, opts).start(new IndexBuilder.Listener()
		{
			@Override
			public void progress(int a, int b, String c)
			{
			}

			@Override
			public void finished(PathIndex index, long ms, String note)
			{
				holder[0] = index;
				done.countDown();
			}
		});
		done.await(5, TimeUnit.MINUTES);
		PathIndex index = holder[0];
		System.out.println("INDEX nodes=" + index.count);

		show(index, "Order7Service", 15);
		show(index, "com.example.trade", 10);
		conn.close();
		System.exit(0);
	}

	private static void show(PathIndex index, String query, int n)
	{
		PathIndex.Result flat = index.search(query, PathIndex.Mode.CONTAINS, 500, null, null, false);
		PathIndex.Result r = index.search(query, PathIndex.Mode.CONTAINS, 500, null, null, true);
		System.out.println("\n=== query=\"" + query + "\" ===");
		System.out.println("flat    : total=" + flat.total + " shown=" + flat.hits.size());
		System.out.println("grouped : interfaces=" + r.total + " rawNodes=" + r.rawTotal
				+ " shown=" + r.hits.size());
		for (int i = 0; i < Math.min(n, r.hits.size()); i++)
		{
			PathIndex.Hit h = r.hits.get(i);
			System.out.println("  [" + i + "] " + (h.interfaceNode ? "IFACE " : "node  ") + h.path
					+ "  providers=" + h.providers + " consumers=" + h.consumers);
		}
		// 展开第一个接口，验证 providers/consumers 与实例都能从索引取到
		if (!r.hits.isEmpty() && r.hits.get(0).interfaceNode)
		{
			String iface = r.hits.get(0).path;
			for (String category : Dubbo.categoriesOf(index.categoriesOf(iface)))
			{
				String catPath = iface + "/" + category;
				java.util.List<String> kids = index.childrenOf(catPath);
				System.out.println("  expand " + category + " -> count=" + index.childCount(catPath)
						+ " listed=" + kids.size());
				for (int i = 0; i < Math.min(2, kids.size()); i++)
				{
					String name = kids.get(i).substring(kids.get(i).lastIndexOf('/') + 1);
					System.out.println("      " + Dubbo.instanceLabel(name) + "   ["
							+ Dubbo.instanceDetail(name) + "]");
				}
			}
		}
	}

	private static void mkdir(ZooKeeper zk, String path) throws Exception
	{
		if (zk.exists(path, false) == null)
		{
			zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		}
	}

	private DubboProbe()
	{
	}
}