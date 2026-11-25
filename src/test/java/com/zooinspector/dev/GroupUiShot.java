/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.zooinspector.dev;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.zooinspector.core.Inspector;
import com.zooinspector.core.PathIndex;
import com.zooinspector.core.Settings;
import com.zooinspector.ui.MainFrame;
import com.zooinspector.ui.Theme;

/** 聚合搜索的界面验证与截图：接口层 -> 点开分类 -> 点开实例 */
public final class GroupUiShot
{
	public static void main(String[] args) throws Exception
	{
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		System.setProperty("zookeeper.sasl.client", "false");
		final String theme = args.length > 0 ? args[0] : "light";
		final File outDir = new File(args.length > 1 ? args[1] : "target/shots");
		final String hosts = args.length > 2 ? args[2] : "127.0.0.1:21811";
		outDir.mkdirs();

		final Inspector[] ih = new Inspector[1];
		final MainFrame[] fh = new MainFrame[1];
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				Settings s = new Settings();
				s.theme = theme;
				Theme.install(s.theme, s.uiFontSize);
				Inspector inspector = new Inspector();
				MainFrame frame = new MainFrame(inspector);
				frame.setSize(1280, 820);
				frame.setLocation(40, 40);
				frame.setVisible(true);
				ih[0] = inspector;
				fh[0] = frame;
			}
		});
		final Inspector inspector = ih[0];
		final MainFrame frame = fh[0];
		run(frame, new Runnable()
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
				return inspector.index().count > 0 && !inspector.isIndexing();
			}
		}, 120000);
		System.out.println("INDEX nodes=" + inspector.index().count);

		// 1. 包名搜索：结果只到接口层
		run(frame, new Runnable()
		{
			@Override
			public void run()
			{
				frame.applySearch("com.example.trade", PathIndex.Mode.CONTAINS, true);
			}
		});
		Thread.sleep(1200);
		capture(frame, new File(outDir, theme + "-group-interfaces.png"));

		// 2. 点开第一个接口 -> 露出 providers / consumers 分类
		run(frame, new Runnable()
		{
			@Override
			public void run()
			{
				frame.resultsPanel().expandRow(0);
			}
		});
		Thread.sleep(700);
		capture(frame, new File(outDir, theme + "-group-categories.png"));

		// 3. 再点开 providers -> 露出实例（解码后的 host:port）
		run(frame, new Runnable()
		{
			@Override
			public void run()
			{
				frame.resultsPanel().expandRow(1);
			}
		});
		Thread.sleep(900);
		capture(frame, new File(outDir, theme + "-group-instances.png"));

		// 4. 精确接口名搜索
		run(frame, new Runnable()
		{
			@Override
			public void run()
			{
				frame.applySearch("Order7Service", PathIndex.Mode.CONTAINS, true);
			}
		});
		Thread.sleep(900);
		run(frame, new Runnable()
		{
			@Override
			public void run()
			{
				frame.resultsPanel().expandRow(0);
			}
		});
		Thread.sleep(600);
		capture(frame, new File(outDir, theme + "-group-single.png"));

		System.out.println("SHOTS=" + outDir.getAbsolutePath());
		System.exit(0);
	}

	private static void run(MainFrame frame, Runnable r) throws Exception
	{
		SwingUtilities.invokeAndWait(r);
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
					System.out.println("SHOT " + file.getName());
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
			}
		});
	}

	private static void waitFor(BooleanSupplier condition, long timeoutMs) throws Exception
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
		throw new IllegalStateException("timeout");
	}

	private GroupUiShot()
	{
	}
}