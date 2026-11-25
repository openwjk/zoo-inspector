/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.zooinspector.dev;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.zooinspector.core.ConnectProfile;
import com.zooinspector.core.IndexBuilder;
import com.zooinspector.core.PathIndex;
import com.zooinspector.core.ZkConnection;

/** 无界面基准：只测索引构建与查询本身，排除渲染开销 */
public final class IndexBench
{
	private static volatile PathIndex built;
	private static volatile long buildMs;
	private static volatile String note;

	public static void main(String[] args) throws Exception
	{
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		System.setProperty("zookeeper.sasl.client", "false");
		String hosts = args.length > 0 ? args[0] : "127.0.0.1:21811";
		boolean withData = args.length > 1 && "data".equals(args[1]);

		ConnectProfile p = new ConnectProfile();
		p.hosts = hosts;
		p.sessionTimeoutMs = 60000;
		ZkConnection conn = new ZkConnection();
		conn.connect(p);

		IndexBuilder.Options opts = new IndexBuilder.Options();
		opts.indexData = withData;
		opts.maxNodes = 500000;
		if (args.length > 2)
		{
			opts.window = Integer.parseInt(args[2]);
		}
		System.out.println("WINDOW=" + opts.window);
		final CountDownLatch latch = new CountDownLatch(1);
		long t0 = System.currentTimeMillis();
		new IndexBuilder(conn, opts).start(new IndexBuilder.Listener()
		{
			@Override
			public void progress(int indexed, int pending, String phase)
			{
			}

			@Override
			public void finished(PathIndex index, long elapsedMs, String message)
			{
				built = index;
				buildMs = elapsedMs;
				note = message;
				latch.countDown();
			}
		});
		if (!latch.await(10, TimeUnit.MINUTES))
		{
			throw new IllegalStateException("index build timed out");
		}
		System.out.println("BUILD nodes=" + built.count + " withData=" + withData + " ms=" + buildMs
				+ " wallMs=" + (System.currentTimeMillis() - t0) + " note=" + note);

		bench("row-00042", PathIndex.Mode.CONTAINS);
		bench("shard-1", PathIndex.Mode.CONTAINS);
		bench("db", PathIndex.Mode.CONTAINS);
		bench("config", PathIndex.Mode.NAME);
		bench("*/config/db", PathIndex.Mode.GLOB);
		bench("^/bulk/shard-[0-9]+/row-000[0-9]{2}$", PathIndex.Mode.REGEX);
		bench("/app/order-api/config/db", PathIndex.Mode.EXACT);
		bench("zzzz-nothing", PathIndex.Mode.CONTAINS);
		if (withData)
		{
			bench("jdbc:mysql", PathIndex.Mode.DATA);
			bench("\"state\":\"active\"", PathIndex.Mode.DATA);
		}
		Runtime rt = Runtime.getRuntime();
		System.out.println("HEAP usedMB=" + (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
		conn.close();
		System.exit(0);
	}

	private static void bench(String query, PathIndex.Mode mode)
	{
		long best = Long.MAX_VALUE;
		long worst = 0;
		PathIndex.Result r = null;
		for (int i = 0; i < 7; i++)
		{
			long t0 = System.nanoTime();
			r = built.search(query, mode, 500, null, null);
			long us = (System.nanoTime() - t0) / 1000L;
			if (us < best)
			{
				best = us;
			}
			if (us > worst)
			{
				worst = us;
			}
		}
		System.out.println("SEARCH " + mode + " \"" + query + "\" total=" + r.total + " shown="
				+ r.hits.size() + " bestUs=" + best + " worstUs=" + worst);
	}

	private IndexBench()
	{
	}
}