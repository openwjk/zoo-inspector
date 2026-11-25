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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

import com.zooinspector.core.PathIndex;
import com.zooinspector.core.ZkConnection;

/** 底部状态栏：连接状态、会话、索引统计与进度 */
public class StatusBar extends JPanel
{
	private final Dot dot = new Dot();
	private final JLabel stateLabel = new JLabel("Disconnected");
	private final JLabel serverLabel = new JLabel("");
	private final JLabel sessionLabel = new JLabel("");
	private final JLabel indexLabel = new JLabel("no index");
	private final JLabel hintLabel = new JLabel("");
	private final JProgressBar progress = new JProgressBar();
	private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

	public StatusBar()
	{
		super(new FlowLayout(FlowLayout.LEFT, 12, 5));
		setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
				Theme.mix(Theme.foreground(), Theme.background(), 0.12f)));
		stateLabel.setFont(Theme.uiFont(java.awt.Font.BOLD, 11));
		serverLabel.setFont(Theme.uiFont(11));
		sessionLabel.setFont(Theme.monoFont(11));
		indexLabel.setFont(Theme.uiFont(11));
		hintLabel.setFont(Theme.uiFont(11));
		for (JLabel l : new JLabel[] { serverLabel, sessionLabel, indexLabel, hintLabel })
		{
			l.setForeground(Theme.muted());
		}
		progress.setPreferredSize(new Dimension(110, 6));
		progress.setVisible(false);
		progress.setBorderPainted(false);

		add(dot);
		add(stateLabel);
		add(serverLabel);
		add(sessionLabel);
		add(Box.createHorizontalStrut(4));
		add(indexLabel);
		add(progress);
		add(hintLabel);
	}

	public void setConnection(ZkConnection.State state, String hosts, long sessionId)
	{
		String text;
		Color color;
		switch (state)
		{
			case CONNECTED:
				text = "CONNECTED";
				color = Theme.success();
				break;
			case READ_ONLY:
				text = "READ-ONLY";
				color = Theme.warn();
				break;
			case CONNECTING:
				text = "CONNECTING";
				color = Theme.warn();
				break;
			case LOST:
				text = "RECONNECTING";
				color = Theme.warn();
				break;
			case EXPIRED:
				text = "SESSION EXPIRED";
				color = Theme.danger();
				break;
			default:
				text = "DISCONNECTED";
				color = Theme.muted();
				break;
		}
		stateLabel.setText(text);
		stateLabel.setForeground(color);
		dot.color = color;
		dot.repaint();
		serverLabel.setText(hosts == null ? "" : hosts);
		sessionLabel.setText(sessionId == 0 ? "" : "session 0x" + Long.toHexString(sessionId));
		revalidate();
		repaint();
	}

	public void setIndex(PathIndex index, String note)
	{
		if (index == null || index.count == 0)
		{
			indexLabel.setText("no index");
		}
		else
		{
			StringBuilder sb = new StringBuilder();
			sb.append("indexed ").append(String.format("%,d", index.count)).append(" nodes · ")
					.append(timeFormat.format(new Date(index.builtAt)));
			if (index.dataIndexed)
			{
				sb.append(" · values");
			}
			indexLabel.setText(sb.toString());
		}
		hintLabel.setText(note == null ? "" : note);
		hintLabel.setForeground(note == null ? Theme.muted() : Theme.warn());
		revalidate();
		repaint();
	}

	public void setProgress(int done, int pending, String phase)
	{
		if (pending <= 0 && done > 0)
		{
			progress.setVisible(false);
			indexLabel.setText("indexed " + String.format("%,d", done) + " nodes");
		}
		else
		{
			progress.setVisible(true);
			progress.setIndeterminate(true);
			indexLabel.setText(phase + "… " + String.format("%,d", done) + " nodes");
		}
		revalidate();
		repaint();
	}

	public void hideProgress()
	{
		progress.setVisible(false);
		repaint();
	}

	/** 重绘时重新取主题色，切换深浅色后不用重建组件 */
	@Override
	public void updateUI()
	{
		super.updateUI();
		if (stateLabel != null)
		{
			setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
					Theme.mix(Theme.foreground(), Theme.background(), 0.12f)));
			for (JLabel l : new JLabel[] { serverLabel, sessionLabel, indexLabel })
			{
				l.setForeground(Theme.muted());
			}
		}
	}

	private static final class Dot extends JComponent
	{
		private Color color = Color.GRAY;

		Dot()
		{
			setPreferredSize(new Dimension(9, 9));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			g2.fillOval(0, 0, 8, 8);
			g2.dispose();
		}
	}
}