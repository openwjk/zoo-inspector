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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;

/** 往本地测试 ZK 里造一棵有几千节点、结构真实的树，用于验证搜索与渲染 */
public final class SeedData
{
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final List<ACL> OPEN = ZooDefs.Ids.OPEN_ACL_UNSAFE;
	private static int created;

	public static void main(String[] args) throws Exception
	{
		String hosts = args.length > 0 ? args[0] : "127.0.0.1:21811";
		final CountDownLatch latch = new CountDownLatch(1);
		ZooKeeper zk = new ZooKeeper(hosts, 30000, new Watcher()
		{
			@Override
			public void process(WatchedEvent event)
			{
				if (event.getState() == Event.KeeperState.SyncConnected)
				{
					latch.countDown();
				}
			}
		});
		if (!latch.await(15, TimeUnit.SECONDS))
		{
			throw new IllegalStateException("cannot connect to " + hosts);
		}
		long t0 = System.currentTimeMillis();

		String[] services = { "order-api", "order-worker", "payment-gateway", "user-center",
				"inventory", "search-index", "notify-hub", "risk-engine", "report-batch",
				"gateway-edge", "audit-log", "pricing" };
		String[] configKeys = { "db", "db-replica", "cache", "mq", "feature-flags", "timeout",
				"retry", "thread-pool", "rate-limit", "circuit-breaker", "tracing", "logging",
				"metrics", "secrets-ref", "endpoints", "healthcheck", "cors", "compression",
				"batch-size", "cron", "ttl", "region", "fallback", "canary" };
		mkdir(zk, "/app");
		for (String svc : services)
		{
			String base = "/app/" + svc;
			mkdir(zk, base);
			mkdir(zk, base + "/config");
			for (String key : configKeys)
			{
				put(zk, base + "/config/" + key, configJson(svc, key));
			}
			mkdir(zk, base + "/instances");
			for (int i = 0; i < 40; i++)
			{
				String p = base + "/instances/10.42." + (i / 20) + "." + (i + 10) + ":" + (8080 + i);
				zk.create(p, ("{\"svc\":\"" + svc + "\",\"weight\":" + (1 + i % 5) + "}").getBytes(UTF8),
						OPEN, CreateMode.EPHEMERAL);
				created++;
			}
			mkdir(zk, base + "/leader");
			zk.create(base + "/leader/latch-", ("owner=" + svc).getBytes(UTF8), OPEN,
					CreateMode.EPHEMERAL_SEQUENTIAL);
			created++;
		}

		mkdir(zk, "/services");
		for (int s = 0; s < 40; s++)
		{
			String base = "/services/rpc-service-" + s;
			mkdir(zk, base);
			mkdir(zk, base + "/providers");
			for (int i = 0; i < 30; i++)
			{
				put(zk, base + "/providers/172.20." + s + "." + (i + 1) + ":20880",
						"{\"version\":\"2.7.\",\"timeout\":" + (1000 + i * 10) + "}");
			}
			mkdir(zk, base + "/consumers");
			for (int i = 0; i < 10; i++)
			{
				put(zk, base + "/consumers/consumer-" + i, "{\"app\":\"caller-" + i + "\"}");
			}
		}

		mkdir(zk, "/config");
		String[] envs = { "dev", "test", "staging", "prod" };
		for (String env : envs)
		{
			mkdir(zk, "/config/" + env);
			for (int m = 0; m < 20; m++)
			{
				String mod = "/config/" + env + "/module-" + m;
				mkdir(zk, mod);
				for (int k = 0; k < 12; k++)
				{
					put(zk, mod + "/key-" + k, "{\"env\":\"" + env + "\",\"value\":" + (k * 7 + m)
							+ ",\"enabled\":" + (k % 2 == 0) + "}");
				}
			}
		}

		mkdir(zk, "/locks");
		for (int g = 0; g < 20; g++)
		{
			String group = "/locks/job-group-" + g;
			mkdir(zk, group);
			for (int i = 0; i < 30; i++)
			{
				zk.create(group + "/lock-", ("holder-" + i).getBytes(UTF8), OPEN,
						CreateMode.PERSISTENT_SEQUENTIAL);
				created++;
			}
		}

		mkdir(zk, "/tenants");
		for (int t = 0; t < 30; t++)
		{
			String base = "/tenants/tenant-" + t;
			mkdir(zk, base);
			for (String leaf : new String[] { "quota", "plan", "region", "contacts", "billing",
					"features", "limits", "branding" })
			{
				put(zk, base + "/" + leaf, "{\"tenant\":" + t + ",\"leaf\":\"" + leaf + "\"}");
			}
		}

		// 一个二进制节点，验证 Hex 视图
		byte[] binary = new byte[256];
		for (int i = 0; i < binary.length; i++)
		{
			binary[i] = (byte) i;
		}
		mkdir(zk, "/binary");
		zk.create("/binary/blob", binary, OPEN, CreateMode.PERSISTENT);
		created++;
		// 一个空节点
		mkdir(zk, "/empty-node");

		System.out.println("SEEDED nodes=" + created + " elapsedMs="
				+ (System.currentTimeMillis() - t0));
		zk.close();
	}

	private static void mkdir(ZooKeeper zk, String path) throws Exception
	{
		if (zk.exists(path, false) == null)
		{
			zk.create(path, new byte[0], OPEN, CreateMode.PERSISTENT);
			created++;
		}
	}

	private static void put(ZooKeeper zk, String path, String data) throws Exception
	{
		if (zk.exists(path, false) == null)
		{
			zk.create(path, data.getBytes(UTF8), OPEN, CreateMode.PERSISTENT);
			created++;
		}
	}

	private static String configJson(String svc, String key)
	{
		if ("db".equals(key))
		{
			return "{\n  \"url\": \"jdbc:mysql://db-primary.internal:3306/" + svc.replace('-', '_')
					+ "\",\n  \"username\": \"" + svc + "_rw\",\n  \"poolSize\": 32,\n"
					+ "  \"connectTimeoutMs\": 3000,\n  \"ssl\": true\n}";
		}
		if ("cache".equals(key))
		{
			return "{\"nodes\":[\"redis-0:6379\",\"redis-1:6379\"],\"ttlSeconds\":600}";
		}
		return "{\"service\":\"" + svc + "\",\"key\":\"" + key + "\",\"enabled\":true}";
	}

	private SeedData()
	{
	}
}