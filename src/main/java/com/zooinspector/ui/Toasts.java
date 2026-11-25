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
package com.zooinspector.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 窗口内右下角浮层通知，取代老版依赖的 jToaster（它会弹出独立小窗口，
 * 在多显示器/高 DPI 下位置经常不对）。同一时刻只保留最新一条。
 */
public final class Toasts
{
	public enum Kind
	{
		INFO, SUCCESS, WARN, ERROR
	}

	private static final String KEY = "zooinspector.toast";

	private Toasts()
	{
	}

	public static void info(Component owner, String message)
	{
		show(owner, message, Kind.INFO);
	}

	public static void success(Component owner, String message)
	{
		show(owner, message, Kind.SUCCESS);
	}

	public static void warn(Component owner, String message)
	{
		show(owner, message, Kind.WARN);
	}

	public static void error(Component owner, String message)
	{
		show(owner, message, Kind.ERROR);
	}

	public static void show(Component owner, final String message, Kind kind)
	{
		final JRootPane root = SwingUtilities.getRootPane(owner);
		if (root == null || !root.isShowing())
		{
			return;
		}
		JLayeredPane layered = root.getLayeredPane();
		Object previous = root.getClientProperty(KEY);
		if (previous instanceof ToastPanel)
		{
			((ToastPanel) previous).dismiss();
		}
		final ToastPanel panel = new ToastPanel(message, kind);
		root.putClientProperty(KEY, panel);
		Dimension pref = panel.getPreferredSize();
		// JLayeredPane 用的是 null 布局：add 时面板还是 0x0，BorderLayout 会算出
		// 负数的子组件尺寸，而 setBounds 之后没有任何人再来validate它 —— 屏幕上就是空框。
		// 所以先给尺寸再显式布局，setBounds 之后再摆一次。
		panel.setSize(pref);
		panel.doLayout();
		layered.add(panel, JLayeredPane.POPUP_LAYER);
		int margin = 18;
		panel.setBounds(layered.getWidth() - pref.width - margin,
				layered.getHeight() - pref.height - margin, pref.width, pref.height);
		panel.doLayout();
		panel.repaint();
		Timer timer = new Timer(kind == Kind.ERROR ? 6500 : 3600, new java.awt.event.ActionListener()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				panel.dismiss();
			}
		});
		timer.setRepeats(false);
		panel.timer = timer;
		timer.start();
	}

	private static final class ToastPanel extends JPanel
	{
		private Timer timer;
		private final Color accent;

		ToastPanel(String message, Kind kind)
		{
			super(new BorderLayout(10, 0));
			setOpaque(false);
			Icon icon;
			switch (kind)
			{
				case SUCCESS:
					icon = Icons.get("check");
					accent = Theme.success();
					break;
				case WARN:
					icon = Icons.get("warning");
					accent = Theme.warn();
					break;
				case ERROR:
					icon = Icons.get("warning");
					accent = Theme.danger();
					break;
				default:
					icon = Icons.get("info");
					accent = Theme.accent();
					break;
			}
			JLabel iconLabel = new JLabel(icon);
			iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
			JLabel text = new JLabel("<html><body style='width:280px'>" + escape(message)
					+ "</body></html>");
			text.setForeground(Theme.foreground());
			add(iconLabel, BorderLayout.WEST);
			add(text, BorderLayout.CENTER);
			setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 16));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					dismiss();
				}
			});
		}

		void dismiss()
		{
			if (timer != null)
			{
				timer.stop();
			}
			java.awt.Container parent = getParent();
			if (parent != null)
			{
				parent.remove(this);
				parent.repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			Color bg = Theme.mix(Theme.background(), Theme.foreground(), 0.97f);
			g2.setColor(new Color(0, 0, 0, Theme.isDark() ? 90 : 34));
			g2.fillRoundRect(2, 3, w - 4, h - 4, 14, 14);
			g2.setColor(bg);
			g2.fillRoundRect(0, 0, w - 4, h - 4, 14, 14);
			g2.setColor(Theme.mix(Theme.foreground(), Theme.background(), 0.16f));
			g2.drawRoundRect(0, 0, w - 5, h - 5, 14, 14);
			g2.setColor(accent);
			g2.fillRoundRect(0, 8, 3, h - 20, 3, 3);
			g2.dispose();
		}
	}

	private static String escape(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}