/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.zooinspector.dev;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.zooinspector.core.Inspector;
import com.zooinspector.core.PathIndex;
import com.zooinspector.core.Settings;
import com.zooinspector.ui.MainFrame;
import com.zooinspector.ui.Theme;

/** 核对两件事：帮助对话框按钮文案、统计文案变长时搜索框宽度是否恒定 */
public final class DialogShot
{
	private static JTextField searchField;

	public static void main(String[] args) throws Exception
	{
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		final File outDir = new File(args.length > 0 ? args[0] : "target/shots");
		outDir.mkdirs();
		final MainFrame[] fh = new MainFrame[1];
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				Settings s = new Settings();
				Theme.install(s.theme, s.uiFontSize);
				MainFrame frame = new MainFrame(new Inspector());
				frame.setSize(1280, 820);
				frame.setLocation(40, 40);
				frame.setVisible(true);
				searchField = findSearchField(frame);
				fh[0] = frame;
			}
		});
		final MainFrame frame = fh[0];
		Thread.sleep(700);

		final int[] idle = new int[1];
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				frame.validate();
				idle[0] = searchField.getWidth();
			}
		});
		System.out.println("SEARCH_FIELD_WIDTH idle=" + idle[0]);

		// 未连接时搜索会返回一条很长的提示文案，正好用来压测宽度
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				frame.applySearch("order", PathIndex.Mode.CONTAINS, true);
			}
		});
		Thread.sleep(900);
		final int[] busy = new int[1];
		SwingUtilities.invokeAndWait(new Runnable()
		{
			@Override
			public void run()
			{
				frame.validate();
				busy[0] = searchField.getWidth();
				System.out.println("SEARCH_FIELD_WIDTH withStats=" + busy[0]);
				System.out.println("WIDTH_STABLE=" + (idle[0] == busy[0]));
				try
				{
					BufferedImage img = new BufferedImage(frame.getWidth(), 150,
							BufferedImage.TYPE_INT_RGB);
					Graphics2D g = img.createGraphics();
					frame.paint(g);
					g.dispose();
					ImageIO.write(img, "png", new File(outDir, "searchbar-width.png"));
					System.out.println("SHOT searchbar-width.png");
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
			}
		});

		SwingUtilities.invokeLater(new Runnable()
		{
			@Override
			public void run()
			{
				frame.showHelp();
			}
		});
		final Timer t = new Timer(1200, null);
		t.setRepeats(false);
		t.addActionListener(new java.awt.event.ActionListener()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				try
				{
					for (Window w : Window.getWindows())
					{
						if (w instanceof JDialog && w.isShowing())
						{
							JDialog d = (JDialog) w;
							d.validate();
							BufferedImage img = new BufferedImage(d.getWidth(), d.getHeight(),
									BufferedImage.TYPE_INT_RGB);
							Graphics2D g = img.createGraphics();
							d.paint(g);
							g.dispose();
							ImageIO.write(img, "png", new File(outDir, "help-dialog.png"));
							System.out.println("SHOT help-dialog.png");
							d.dispose();
						}
					}
				}
				catch (Exception ex)
				{
					ex.printStackTrace();
				}
				System.exit(0);
			}
		});
		SwingUtilities.invokeLater(new Runnable()
		{
			@Override
			public void run()
			{
				t.start();
			}
		});
		Thread.sleep(15000);
		System.exit(0);
	}

	private static JTextField findSearchField(Container root)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JTextField)
			{
				Object ph = ((JTextField) c).getClientProperty("JTextField.placeholderText");
				if (ph != null && String.valueOf(ph).startsWith("Search node paths"))
				{
					return (JTextField) c;
				}
			}
			if (c instanceof Container)
			{
				JTextField found = findSearchField((Container) c);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private DialogShot()
	{
	}
}