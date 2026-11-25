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

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.zooinspector.core.Inspector;
import com.zooinspector.core.Settings;
import com.zooinspector.ui.MainFrame;
import com.zooinspector.ui.Theme;
import com.zooinspector.ui.Toasts;

/** 复现右下角通知框：不需要连接，直接触发 toast 后截图 */
public final class ToastShot
{
	public static void main(String[] args) throws Exception
	{
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		final String theme = args.length > 0 ? args[0] : "light";
		final File outDir = new File(args.length > 1 ? args[1] : "target/shots");
		outDir.mkdirs();
		final MainFrame[] fh = new MainFrame[1];
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				Settings s = new Settings();
				s.theme = theme;
				Theme.install(s.theme, s.uiFontSize);
				MainFrame frame = new MainFrame(new Inspector());
				frame.setSize(1100, 700);
				frame.setLocation(40, 40);
				frame.setVisible(true);
				fh[0] = frame;
			}
		});
		final MainFrame frame = fh[0];
		Thread.sleep(800);
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				Toasts.success(frame, "Path copied");
			}
		});
		Thread.sleep(500);
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					// 故意不调 frame.validate()：真实屏幕上没人替 toast 做布局，
					// 这里保持和真实渲染路径一致
					BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(),
							BufferedImage.TYPE_INT_RGB);
					Graphics2D g = img.createGraphics();
					frame.paint(g);
					g.dispose();
					ImageIO.write(img, "png", new File(outDir, theme + "-toast.png"));
					// 顺带把 toast 组件的实际尺寸打出来
					Object t = frame.getRootPane().getClientProperty("zooinspector.toast");
					System.out.println("toast component = " + t);
					if (t instanceof java.awt.Component)
					{
						java.awt.Component c = (java.awt.Component) t;
						System.out.println("bounds=" + c.getBounds() + " pref="
								+ c.getPreferredSize() + " showing=" + c.isShowing()
								+ " children=" + ((java.awt.Container) c).getComponentCount());
						for (java.awt.Component kid : ((java.awt.Container) c).getComponents())
						{
							System.out.println("  kid " + kid.getClass().getSimpleName()
									+ " bounds=" + kid.getBounds() + " pref=" + kid.getPreferredSize());
						}
					}
					System.out.println("SHOT " + theme + "-toast.png");
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
			}
		});
		System.exit(0);
	}

	private ToastShot()
	{
	}
}