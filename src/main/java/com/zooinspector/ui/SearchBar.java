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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.zooinspector.core.PathIndex;

/** 顶部搜索条：输入即搜，模式可切换，右侧实时显示命中数与耗时 */
public class SearchBar extends JPanel
{
	public interface Listener
	{
		void queryChanged(String text, PathIndex.Mode mode);

		void enterPressed();

		void escapePressed();

		void downPressed();
	}

	private final JTextField field = new JTextField();
	private final JComboBox<PathIndex.Mode> modeBox = new JComboBox<PathIndex.Mode>();
	private final javax.swing.JCheckBox groupBox = new javax.swing.JCheckBox("Group interfaces", true);
	private final JButton clear = new JButton(Icons.get("close"));
	private final JLabel stats = new JLabel("");
	private Listener listener;
	private boolean suppress;

	public SearchBar()
	{
		super(new BorderLayout(8, 0));
		setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

		field.putClientProperty("JTextField.placeholderText",
				"Search node paths…   Ctrl+F");
		field.putClientProperty("JTextField.leadingIcon", Icons.get("search"));
		field.putClientProperty("JTextField.showClearButton", false);
		field.setColumns(28);
		field.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				fire();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				fire();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				fire();
			}
		});
		field.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ENTER && listener != null)
				{
					listener.enterPressed();
				}
				else if (e.getKeyCode() == KeyEvent.VK_ESCAPE && listener != null)
				{
					listener.escapePressed();
				}
				else if (e.getKeyCode() == KeyEvent.VK_DOWN && listener != null)
				{
					listener.downPressed();
				}
			}
		});

		modeBox.setModel(new DefaultComboBoxModel<PathIndex.Mode>(PathIndex.Mode.values()));
		modeBox.setSelectedItem(PathIndex.Mode.CONTAINS);
		modeBox.setPreferredSize(new Dimension(112, 28));
		modeBox.setToolTipText("Contains: substring of the full path · Name only: last segment · "
				+ "Glob: * and ? · Regex: Java regular expression · Data: search node values");
		modeBox.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				fireNow();
			}
		});

		clear.setPreferredSize(new Dimension(28, 28));
		clear.setToolTipText("Clear search (Esc)");
		clear.putClientProperty("JButton.buttonType", "borderless");
		clear.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				clearSearch();
			}
		});

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		right.setOpaque(false);
		clear.setVisible(false);
		right.add(clear);
		stats.setFont(Theme.uiFont(11));
		stats.setForeground(Theme.muted());
		right.add(stats);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		controls.setOpaque(false);
		controls.add(modeBox);
		groupBox.setOpaque(false);
		groupBox.setToolTipText("Roll matches up to the Dubbo interface node "
				+ "(the level holding providers/consumers). Expand a row to see instances.");
		groupBox.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				fireNow();
			}
		});
		controls.add(groupBox);

		add(field, BorderLayout.CENTER);
		JPanel east = new JPanel(new BorderLayout(6, 0));
		east.setOpaque(false);
		east.add(controls, BorderLayout.WEST);
		east.add(right, BorderLayout.EAST);
		add(east, BorderLayout.EAST);
	}

	public void setListener(Listener l)
	{
		this.listener = l;
	}

	public String text()
	{
		return field.getText();
	}

	public PathIndex.Mode mode()
	{
		Object sel = modeBox.getSelectedItem();
		return sel instanceof PathIndex.Mode ? (PathIndex.Mode) sel : PathIndex.Mode.CONTAINS;
	}

	public void focusField()
	{
		field.requestFocusInWindow();
		field.selectAll();
	}

	/** 供命令行/自动化驱动搜索用 */
	public void setText(String text)
	{
		field.setText(text);
	}

	public void setMode(PathIndex.Mode mode)
	{
		modeBox.setSelectedItem(mode);
	}

	public boolean isGrouped()
	{
		return groupBox.isSelected();
	}

	public void setGrouped(boolean grouped)
	{
		groupBox.setSelected(grouped);
	}

	public void clearSearch()
	{
		suppress = true;
		field.setText("");
		suppress = false;
		clear.setVisible(false);
		stats.setText("");
		if (listener != null)
		{
			listener.queryChanged("", mode());
		}
	}

	public void setStats(String text)
	{
		stats.setText(text);
	}

	private void fire()
	{
		if (suppress)
		{
			return;
		}
		clear.setVisible(!field.getText().isEmpty());
		if (listener != null)
		{
			listener.queryChanged(field.getText(), mode());
		}
	}

	private void fireNow()
	{
		if (suppress)
		{
			return;
		}
		if (listener != null)
		{
			listener.queryChanged(field.getText(), mode());
		}
	}

	@Override
	public void updateUI()
	{
		super.updateUI();
		if (stats != null)
		{
			stats.setForeground(Theme.muted());
			field.putClientProperty("JTextField.leadingIcon", Icons.get("search"));
			clear.setIcon(Icons.get("close"));
		}
	}
}