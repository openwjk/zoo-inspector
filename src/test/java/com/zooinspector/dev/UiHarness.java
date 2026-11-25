/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.zooinspector.dev;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.zooinspector.core.Inspector;
import com.zooinspector.core.PathIndex;
import com.zooinspector.core.Settings;
import com.zooinspector.ui.MainFrame;
import com.zooinspector.ui.Theme;

/**
 * 端到端验证 + 截图。只连本地 DevServer。
 *
 * 用法：java ... com.zooinspector.dev.UiHarness <light|dark> <outDir> [hosts]
 */
public final class UiHarness
{
	private static volatile long maxEdtGap;
	private static volatile long lastTick;

	public static void main(String[] args) throws Exception
	{
		final String theme = args.length > 0 ? args[0] : "light";
		final File outDir = new File(args.length > 1 ? args[1] : "target/shots");
		final String hosts = args.length > 2 ? args[2] : "127.0.0.1:21811";
		outDir.mkdirs();

		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		System.setProperty("zookeeper.sasl.client", "false");

		final Settings settings = new Settings();
		settings.theme = theme;
		settings.indexOnConnect = true;

		final Inspector[] holder = new Inspector[1];
		final MainFrame[] frameHolder = new MainFrame[1];
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				Theme.install(settings.theme, settings.uiFontSize);
				Inspector inspector = new Inspector();
				MainFrame frame = new MainFrame(inspector);
				frame.setSize(1280, 820);
				frame.setLocation(40, 40);
				frame.setVisible(true);
				frame.setAlwaysOnTop(true);
				frame.toFront();
				holder[0] = inspector;
				frameHolder[0] = frame;
				// EDT 卡顿看门狗：记录相邻 tick 的最大间隔
				lastTick = System.nanoTime();
				new Timer(20, new java.awt.event.ActionListener()
				{
					@Override
					public void actionPerformed(java.awt.event.ActionEvent e)
					{
						long now = System.nanoTime();
						long gap = (now - lastTick) / 1000000L;
						if (gap > maxEdtGap)
						{
							maxEdtGap = gap;
						}
						lastTick = now;
					}
				}).start();
			}
		});
		final Inspector inspector = holder[0];
		final MainFrame frame = frameHolder[0];

		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				frame.connectHosts(hosts);
			}
		});
		waitFor(new BooleanSupplier()
		{
			@Override
			public boolean getAsBoolean()
			{
				return inspector.connection().isUsable();
			}
		}, 20000, "connect");

		waitFor(new BooleanSupplier()
		{
			@Override
			public boolean getAsBoolean()
			{
				return inspector.index().count > 0 && !inspector.isIndexing();
			}
		}, 60000, "index");
		Thread.sleep(600);
		System.out.println("INDEX nodes=" + inspector.index().count);
		System.out.println("EDT_MAX_GAP_MS_STARTUP=" + maxEdtGap);
		maxEdtGap = 0;

		// 直接对索引做查询基准
		bench(inspector.index(), "db", PathIndex.Mode.CONTAINS);
		bench(inspector.index(), "config", PathIndex.Mode.NAME);
		bench(inspector.index(), "*/config/db", PathIndex.Mode.GLOB);
		bench(inspector.index(), "^/app/[a-z-]+/instances/.*", PathIndex.Mode.REGEX);
		bench(inspector.index(), "/app/order-api/config/db", PathIndex.Mode.EXACT);
		bench(inspector.index(), "zzz-not-there", PathIndex.Mode.CONTAINS);

		// 展开一棵大子树，检验 EDT 是否被网络请求阻塞
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				frame.treePanel().reveal("/services/rpc-service-3/providers");
			}
		});
		Thread.sleep(1500);
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				frame.treePanel().reveal("/app/order-api/config/db");
			}
		});
		Thread.sleep(1500);
		System.out.println("EDT_MAX_GAP_MS_BROWSE=" + maxEdtGap);
		capture(frame, new File(outDir, theme + "-tree.png"));
		maxEdtGap = 0;

		// 另外三个 tab 的渲染验证
		final String[] tabNames = { "stat", "acl", "children" };
		for (int t = 1; t <= 3; t++)
		{
			final int index = t;
			SwingUtilities.invokeAndWait(new Runnable()
			{
				@Override
				public void run()
				{
					if (index == 3)
					{
						// Children 表要有内容才看得出效果
						frame.treePanel().reveal("/app/order-api/config");
					}
					frame.detailPanel().selectTab(index);
				}
			});
			Thread.sleep(1600);
			capture(frame, new File(outDir, theme + "-" + tabNames[t - 1] + ".png"));
		}
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				frame.detailPanel().selectTab(0);
			}
		});
		Thread.sleep(500);

		// 搜索结果视图
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				frame.applySearch("db", PathIndex.Mode.CONTAINS);
			}
		});
		Thread.sleep(900);
		System.out.println("EDT_MAX_GAP_MS_SEARCH=" + maxEdtGap);
		capture(frame, new File(outDir, theme + "-search.png"));

		// 内容索引 + Data 搜索
		final long dataStart = System.currentTimeMillis();
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				inspector.rebuildIndex(true);
			}
		});
		waitFor(new BooleanSupplier()
		{
			@Override
			public boolean getAsBoolean()
			{
				return inspector.index().dataIndexed && !inspector.isIndexing();
			}
		}, 120000, "data index");
		System.out.println("DATA_INDEX nodes=" + inspector.index().count + " elapsedMs="
				+ (System.currentTimeMillis() - dataStart) + " skipped="
				+ inspector.index().dataSkipped);
		bench(inspector.index(), "jdbc:mysql", PathIndex.Mode.DATA);
		bench(inspector.index(), "redis-0", PathIndex.Mode.DATA);

		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				frame.applySearch("jdbc:mysql", PathIndex.Mode.DATA);
			}
		});
		Thread.sleep(1000);
		capture(frame, new File(outDir, theme + "-data-search.png"));

		System.out.println("EDT_MAX_GAP_MS_FINAL=" + maxEdtGap);

		// 中文节点名 / 中文查询：验证字体不是空心方块
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				frame.applySearch("中文", PathIndex.Mode.CONTAINS, false);
			}
		});
		Thread.sleep(900);
		capture(frame, new File(outDir, theme + "-cjk.png"));

		System.out.println("SHOTS=" + outDir.getAbsolutePath());
		System.exit(0);
	}

	private static void bench(PathIndex index, String query, PathIndex.Mode mode)
	{
		long best = Long.MAX_VALUE;
		PathIndex.Result result = null;
		for (int i = 0; i < 5; i++)
		{
			long t0 = System.nanoTime();
			result = index.search(query, mode, 500, null, null);
			long ms = (System.nanoTime() - t0) / 1000000L;
			if (ms < best)
			{
				best = ms;
			}
		}
		System.out.println("SEARCH mode=" + mode + " query=" + query + " total=" + result.total
				+ " shown=" + result.hits.size() + " bestMs=" + best);
	}

	private static void capture(final MainFrame frame, final File file) throws Exception
	{
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					frame.validate();
					BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(),
							BufferedImage.TYPE_INT_RGB);
					Graphics2D g = img.createGraphics();
					frame.paint(g);
					g.dispose();
					ImageIO.write(img, "png", file);
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
			}
		});
		// 另外用 Robot 抓一张真实屏幕，交叉核对渲染效果
		try
		{
			Robot robot = new Robot();
			Rectangle bounds = new Rectangle(frame.getLocationOnScreen().x,
					frame.getLocationOnScreen().y, frame.getWidth(), frame.getHeight());
			BufferedImage shot = robot.createScreenCapture(bounds);
			File robotFile = new File(file.getParentFile(),
					file.getName().replace(".png", "-robot.png"));
			ImageIO.write(shot, "png", robotFile);
		}
		catch (Exception e)
		{
			System.out.println("robot capture skipped: " + e);
		}
		System.out.println("SHOT " + file.getName());
	}

	private static void waitFor(BooleanSupplier condition, long timeoutMs, String what)
			throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline)
		{
			if (condition.getAsBoolean())
			{
				return;
			}
			Thread.sleep(100);
		}
		throw new IllegalStateException("timeout waiting for " + what);
	}

	private UiHarness()
	{
	}
}