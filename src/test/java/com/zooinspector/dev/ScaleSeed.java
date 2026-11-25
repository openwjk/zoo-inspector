/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.zooinspector.dev;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

/** 造十万级节点，用于验证索引与搜索在大规模下的表现（异步流水线创建） */
public final class ScaleSeed
{
	private static final Charset UTF8 = Charset.forName("UTF-8");

	public static void main(String[] args) throws Exception
	{
		String hosts = args.length > 0 ? args[0] : "127.0.0.1:21811";
		int shards = args.length > 1 ? Integer.parseInt(args[1]) : 200;
		int rows = args.length > 2 ? Integer.parseInt(args[2]) : 500;

		final CountDownLatch connected = new CountDownLatch(1);
		final ZooKeeper zk = new ZooKeeper(hosts, 60000, new Watcher()
		{
			@Override
			public void process(WatchedEvent event)
			{
				if (event.getState() == Event.KeeperState.SyncConnected)
				{
					connected.countDown();
				}
			}
		});
		if (!connected.await(20, TimeUnit.SECONDS))
		{
			throw new IllegalStateException("cannot connect " + hosts);
		}
		long t0 = System.currentTimeMillis();
		if (zk.exists("/bulk", false) == null)
		{
			zk.create("/bulk", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		}
		final Deque<String> queue = new ArrayDeque<String>();
		for (int s = 0; s < shards; s++)
		{
			String shard = "/bulk/shard-" + String.format("%03d", s);
			if (zk.exists(shard, false) == null)
			{
				zk.create(shard, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			}
			for (int r = 0; r < rows; r++)
			{
				queue.add(shard + "/row-" + String.format("%05d", r));
			}
		}
		final int total = queue.size();
		final CountDownLatch done = new CountDownLatch(1);
		final Object lock = new Object();
		final int[] inFlight = { 0 };
		final int[] finished = { 0 };
		final int window = 512;

		final AsyncCallback.StringCallback cb = new AsyncCallback.StringCallback()
		{
			@Override
			public void processResult(int rc, String path, Object ctx, String name)
			{
				synchronized (lock)
				{
					inFlight[0]--;
					finished[0]++;
					pump(zk, queue, lock, inFlight, finished, total, window, done, this);
				}
			}
		};
		synchronized (lock)
		{
			pump(zk, queue, lock, inFlight, finished, total, window, done, cb);
		}
		if (!done.await(10, TimeUnit.MINUTES))
		{
			throw new IllegalStateException("seeding timed out at " + finished[0]);
		}
		System.out.println("SCALE_SEEDED nodes=" + total + " elapsedMs="
				+ (System.currentTimeMillis() - t0));
		zk.close();
	}

	private static void pump(ZooKeeper zk, Deque<String> queue, Object lock, int[] inFlight,
			int[] finished, int total, int window, CountDownLatch done,
			AsyncCallback.StringCallback cb)
	{
		while (inFlight[0] < window && !queue.isEmpty())
		{
			String path = queue.poll();
			inFlight[0]++;
			byte[] data = ("{\"row\":\"" + path + "\",\"state\":\"active\",\"score\":"
					+ (path.hashCode() & 0xFFFF) + "}").getBytes(UTF8);
			zk.create(path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, cb, null);
		}
		if (finished[0] >= total)
		{
			done.countDown();
		}
	}

	private ScaleSeed()
	{
	}
}