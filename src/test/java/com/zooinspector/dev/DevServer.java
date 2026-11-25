/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.zooinspector.dev;

import java.io.File;
import java.net.InetSocketAddress;

import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;

/**
 * 本地一次性 ZooKeeper，仅用于开发验证。
 * 绝不连接用户环境里的真实集群。
 *
 * 用法：java ... com.zooinspector.dev.DevServer [port] [dataDir]
 */
public final class DevServer
{
	public static void main(String[] args) throws Exception
	{
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 21811;
		File dir = new File(args.length > 1 ? args[1] : "target/devzk");
		File snap = new File(dir, "snap");
		File log = new File(dir, "log");
		snap.mkdirs();
		log.mkdirs();

		ZooKeeperServer server = new ZooKeeperServer(snap, log, 2000);
		NIOServerCnxnFactory factory = new NIOServerCnxnFactory();
		factory.configure(new InetSocketAddress("127.0.0.1", port), 500);
		factory.startup(server);
		System.out.println("READY port=" + port + " data=" + dir.getAbsolutePath());
		System.out.flush();
		Thread.currentThread().join();
	}

	private DevServer()
	{
	}
}