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
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import com.zooinspector.core.Inspector;

/** 右侧详情：路径头 + Data / Stat / ACL / Children 四个 tab，按需加载 */
public class NodeDetailPanel extends JPanel
{
	public interface Listener
	{
		void revealInTree(String path);

		void navigate(String path);
	}

	private final Inspector inspector;
	private final DataPanel dataPanel;
	private final StatPanel statPanel;
	private final AclPanel aclPanel;
	private final ChildrenPanel childrenPanel;
	private final JTabbedPane tabs = new JTabbedPane();
	private final JTextField pathField = new JTextField();
	private final JLabel placeholder = new JLabel(
			"<html><div style='text-align:center'>Select a node in the tree,<br>"
					+ "or search for one with <b>Ctrl+F</b>.</div></html>", JLabel.CENTER);
	private final JPanel content = new JPanel(new BorderLayout());
	private final boolean[] loaded = new boolean[4];
	private String path;
	private Listener listener;

	public NodeDetailPanel(Inspector inspector)
	{
		super(new BorderLayout());
		this.inspector = inspector;
		dataPanel = new DataPanel(inspector);
		statPanel = new StatPanel(inspector);
		aclPanel = new AclPanel(inspector);
		childrenPanel = new ChildrenPanel(inspector);
		childrenPanel.setListener(new ChildrenPanel.Listener()
		{
			@Override
			public void navigate(String childPath)
			{
				if (listener != null)
				{
					listener.navigate(childPath);
				}
			}
		});

		tabs.addTab("Data", Icons.get("data"), dataPanel);
		tabs.addTab("Stat", Icons.get("info"), statPanel);
		tabs.addTab("ACL", Icons.get("lock"), aclPanel);
		tabs.addTab("Children", Icons.get("children"), childrenPanel);
		tabs.addChangeListener(new ChangeListener()
		{
			@Override
			public void stateChanged(ChangeEvent e)
			{
				loadCurrentTab();
			}
		});

		pathField.setEditable(false);
		pathField.setFont(Theme.monoFont(inspector.settings().monoFontSize));
		pathField.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		pathField.putClientProperty("JTextField.background", null);

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
		JButton copy = new JButton(Icons.get("copy"));
		copy.setToolTipText("Copy path (Ctrl+Shift+C)");
		copy.putClientProperty("JButton.buttonType", "borderless");
		copy.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				copyPath();
			}
		});
		JButton reveal = new JButton(Icons.get("reveal"));
		reveal.setToolTipText("Reveal in tree");
		reveal.putClientProperty("JButton.buttonType", "borderless");
		reveal.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				if (path != null && listener != null)
				{
					listener.revealInTree(path);
				}
			}
		});
		JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
		actions.setOpaque(false);
		actions.add(reveal);
		actions.add(copy);
		header.add(pathField, BorderLayout.CENTER);
		header.add(actions, BorderLayout.EAST);

		content.add(tabs, BorderLayout.CENTER);
		placeholder.setForeground(Theme.muted());
		add(header, BorderLayout.NORTH);
		add(placeholder, BorderLayout.CENTER);
		header.setVisible(false);
	}

	public void setListener(Listener l)
	{
		this.listener = l;
	}

	public String currentPath()
	{
		return path;
	}

	public boolean hasUnsavedChanges()
	{
		return dataPanel.hasUnsavedChanges();
	}

	public boolean confirmDiscard()
	{
		return dataPanel.confirmDiscard();
	}

	public void saveData()
	{
		tabs.setSelectedIndex(0);
		dataPanel.saveNow();
	}

	public void focusDataFind()
	{
		tabs.setSelectedIndex(0);
		dataPanel.focusFind();
	}

	public void clear()
	{
		path = null;
		for (int i = 0; i < loaded.length; i++)
		{
			loaded[i] = false;
		}
		dataPanel.clear();
		statPanel.clear();
		aclPanel.clear();
		childrenPanel.clear();
		pathField.setText("");
		remove(content);
		add(placeholder, BorderLayout.CENTER);
		((JPanel) getComponent(0)).setVisible(false);
		revalidate();
		repaint();
	}

	/** 切换节点。调用方需先确认未保存的编辑 */
	public void show(String nodePath)
	{
		this.path = nodePath;
		for (int i = 0; i < loaded.length; i++)
		{
			loaded[i] = false;
		}
		pathField.setText(nodePath);
		pathField.setCaretPosition(0);
		remove(placeholder);
		add(content, BorderLayout.CENTER);
		getComponent(0).setVisible(true);
		revalidate();
		repaint();
		loadCurrentTab();
	}

	/** 只加载当前 tab，其余等切过去再拉，避免每次选中都打 4 组请求 */
	private void loadCurrentTab()
	{
		if (path == null)
		{
			return;
		}
		int index = tabs.getSelectedIndex();
		if (index < 0 || index >= loaded.length || loaded[index])
		{
			return;
		}
		loaded[index] = true;
		switch (index)
		{
			case 0:
				dataPanel.load(path, true);
				break;
			case 1:
				statPanel.load(path);
				break;
			case 2:
				aclPanel.load(path);
				break;
			default:
				childrenPanel.load(path);
				break;
		}
	}

	/** 保存/删除后强制重载当前 tab */
	public void reloadCurrent()
	{
		if (path == null)
		{
			return;
		}
		int index = tabs.getSelectedIndex();
		if (index >= 0 && index < loaded.length)
		{
			loaded[index] = false;
		}
		loadCurrentTab();
	}

	public void copyPath()
	{
		if (path == null)
		{
			return;
		}
		Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(path), null);
		Toasts.success(this, "Path copied");
	}

	public void selectDataTab()
	{
		tabs.setSelectedIndex(0);
		dataPanel.focusEditor();
	}

	/** 供自动化验证切换 tab */
	public void selectTab(int index)
	{
		if (index >= 0 && index < tabs.getTabCount())
		{
			tabs.setSelectedIndex(index);
		}
	}

	@Override
	public void updateUI()
	{
		super.updateUI();
		if (placeholder != null)
		{
			placeholder.setForeground(Theme.muted());
			pathField.setFont(Theme.monoFont(inspector.settings().monoFontSize));
		}
	}
}