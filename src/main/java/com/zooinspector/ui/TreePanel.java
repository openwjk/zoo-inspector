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
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import com.zooinspector.core.Inspector;
import com.zooinspector.core.NodeSnapshot;
import com.zooinspector.core.ZkConnection;
import com.zooinspector.core.ZkTreeModel;
import com.zooinspector.util.Edt;
import com.zooinspector.util.ZkPaths;

/** 左侧节点树：缓存模型 + 自绘行渲染 + 右键操作 + 可选 watch */
public class TreePanel extends JPanel
{
	public interface Actions
	{
		void createChild(String parentPath);

		void deleteNode(String path);

		void editData(String path);

		void copyPath(String path);

		void copyData(String path);
	}

	private final Inspector inspector;
	private final JTree tree;
	private final Map<String, Boolean> watched = new ConcurrentHashMap<String, Boolean>();
	private Actions actions;
	private NodeSelectionListener selectionListener;

	public interface NodeSelectionListener
	{
		void nodeSelected(String path);
	}

	public TreePanel(Inspector inspector)
	{
		super(new BorderLayout());
		this.inspector = inspector;
		tree = new JTree(inspector.tree());
		tree.setRootVisible(true);
		tree.setShowsRootHandles(true);
		tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		tree.setCellRenderer(new NodeRenderer());
		tree.setToggleClickCount(2);
		tree.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		tree.addTreeWillExpandListener(new TreeWillExpandListener()
		{
			@Override
			public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException
			{
				Object last = event.getPath().getLastPathComponent();
				if (last instanceof ZkTreeModel.Node)
				{
					inspector.tree().ensureLoaded((ZkTreeModel.Node) last);
				}
			}

			@Override
			public void treeWillCollapse(TreeExpansionEvent event)
			{
			}
		});

		tree.addTreeSelectionListener(new javax.swing.event.TreeSelectionListener()
		{
			@Override
			public void valueChanged(javax.swing.event.TreeSelectionEvent e)
			{
				String path = selectedPath();
				if (path != null && selectionListener != null)
				{
					selectionListener.nodeSelected(path);
				}
			}
		});

		tree.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybePopup(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybePopup(e);
			}
		});

		JScrollPane scroll = new JScrollPane(tree);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		add(scroll, BorderLayout.CENTER);
	}

	public void setActions(Actions actions)
	{
		this.actions = actions;
	}

	public void setSelectionListener(NodeSelectionListener l)
	{
		this.selectionListener = l;
	}

	public JTree tree()
	{
		return tree;
	}

	public String selectedPath()
	{
		TreePath tp = tree.getSelectionPath();
		if (tp == null)
		{
			return null;
		}
		Object last = tp.getLastPathComponent();
		if (last instanceof ZkTreeModel.Node)
		{
			ZkTreeModel.Node n = (ZkTreeModel.Node) last;
			return n.placeholder ? null : n.path;
		}
		return null;
	}

	public void clear()
	{
		tree.clearSelection();
		watched.clear();
	}

	/** 依次异步加载祖先并展开，最后选中目标节点 */
	public void reveal(final String path)
	{
		final List<String> chain = ZkPaths.ancestors(path);
		expandChain(chain, 0);
	}

	private void expandChain(final List<String> chain, final int index)
	{
		if (index >= chain.size())
		{
			return;
		}
		final ZkTreeModel.Node node = inspector.tree().nodeFor(chain.get(index));
		if (node == null)
		{
			// 祖先还没出现在缓存里，先加载上一级
			ZkTreeModel.Node parent = index == 0 ? inspector.tree().root()
					: inspector.tree().nodeFor(chain.get(index - 1));
			if (parent == null)
			{
				return;
			}
			inspector.tree().ensureLoaded(parent, new Runnable()
			{
				@Override
				public void run()
				{
					expandChain(chain, index);
				}
			});
			return;
		}
		inspector.tree().ensureLoaded(node, new Runnable()
		{
			@Override
			public void run()
			{
				TreePath tp = treePathFor(node);
				if (index == chain.size() - 1)
				{
					tree.setSelectionPath(tp);
					tree.scrollPathToVisible(tp);
				}
				else
				{
					tree.expandPath(tp);
					expandChain(chain, index + 1);
				}
			}
		});
	}

	private TreePath treePathFor(ZkTreeModel.Node node)
	{
		List<ZkTreeModel.Node> chain = new ArrayList<ZkTreeModel.Node>();
		ZkTreeModel.Node cur = node;
		while (cur != null)
		{
			chain.add(0, cur);
			cur = cur.parent;
		}
		return new TreePath(chain.toArray());
	}

	public void refreshSubtree(String path)
	{
		ZkTreeModel.Node n = path == null ? inspector.tree().root()
				: inspector.tree().nodeFor(path);
		if (n == null)
		{
			n = inspector.tree().root();
		}
		inspector.tree().reload(n);
	}

	private void maybePopup(MouseEvent e)
	{
		if (!e.isPopupTrigger())
		{
			return;
		}
		int row = tree.getClosestRowForLocation(e.getX(), e.getY());
		if (row >= 0)
		{
			tree.setSelectionRow(row);
		}
		final String path = selectedPath();
		if (path == null)
		{
			return;
		}
		JPopupMenu menu = new JPopupMenu();
		menu.add(item("New child node…", "plus", new Runnable()
		{
			@Override
			public void run()
			{
				if (actions != null)
				{
					actions.createChild(path);
				}
			}
		}));
		menu.add(item("Edit data", "data", new Runnable()
		{
			@Override
			public void run()
			{
				if (actions != null)
				{
					actions.editData(path);
				}
			}
		}));
		menu.add(new JSeparator());
		menu.add(item("Copy path", "copy", new Runnable()
		{
			@Override
			public void run()
			{
				if (actions != null)
				{
					actions.copyPath(path);
				}
			}
		}));
		menu.add(item("Copy data", "copy", new Runnable()
		{
			@Override
			public void run()
			{
				if (actions != null)
				{
					actions.copyData(path);
				}
			}
		}));
		menu.add(new JSeparator());
		menu.add(item("Refresh subtree", "refresh", new Runnable()
		{
			@Override
			public void run()
			{
				refreshSubtree(path);
			}
		}));
		final boolean isWatched = watched.containsKey(path);
		menu.add(item(isWatched ? "Stop watching" : "Watch changes", "watch", new Runnable()
		{
			@Override
			public void run()
			{
				if (isWatched)
				{
					watched.remove(path);
					Toasts.info(TreePanel.this, "Stopped watching " + path);
				}
				else
				{
					armWatch(path);
				}
			}
		}));
		menu.add(new JSeparator());
		JMenuItem delete = item("Delete node…", "trash", new Runnable()
		{
			@Override
			public void run()
			{
				if (actions != null)
				{
					actions.deleteNode(path);
				}
			}
		});
		delete.setForeground(Theme.danger());
		menu.add(delete);
		menu.show(tree, e.getX(), e.getY());
	}

	private JMenuItem item(String text, String icon, final Runnable action)
	{
		JMenuItem mi = new JMenuItem(text, Icons.get(icon));
		mi.addActionListener(new java.awt.event.ActionListener()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				action.run();
			}
		});
		return mi;
	}

	/** 一次性 watch + 触发后重挂，只覆盖用户显式关注的节点，不给集群加负担 */
	private void armWatch(final String path)
	{
		watched.put(path, Boolean.TRUE);
		Edt.background(new Callable<String>()
		{
			@Override
			public String call()
			{
				try
				{
					inspector.connection().childrenWatched(path, new Watcher()
					{
						@Override
						public void process(WatchedEvent event)
						{
							if (!watched.containsKey(path))
							{
								return;
							}
							Edt.run(new Runnable()
							{
								@Override
								public void run()
								{
									refreshSubtree(path);
									inspector.reindexSubtree(path);
									Toasts.info(TreePanel.this, "Changed: " + path);
									armWatch(path);
								}
							});
						}
					});
					return null;
				}
				catch (Exception e)
				{
					return Inspector.message(e);
				}
			}
		}, new Edt.Callback<String>()
		{
			@Override
			public void accept(String error)
			{
				if (error != null)
				{
					watched.remove(path);
					Toasts.error(TreePanel.this, "Watch failed: " + error);
				}
			}
		}, null);
	}

	/** 行渲染：图标 + 名字 + 右侧淡色 badge（子节点数 / 数据大小） */
	private static final class NodeRenderer extends JPanel implements javax.swing.tree.TreeCellRenderer
	{
		private final JLabel iconLabel = new JLabel();
		private final JLabel nameLabel = new JLabel();
		private final JLabel badgeLabel = new JLabel();

		NodeRenderer()
		{
			super(new BorderLayout(6, 0));
			setOpaque(true);
			add(iconLabel, BorderLayout.WEST);
			add(nameLabel, BorderLayout.CENTER);
			add(badgeLabel, BorderLayout.EAST);
			badgeLabel.setFont(Theme.uiFont(11));
			setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 8));
		}

		@Override
		public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
				boolean expanded, boolean leaf, int row, boolean hasFocus)
		{
			Color bg = selected ? Theme.selectionBackground("Tree", tree.getBackground(),
					tree.hasFocus()) : tree.getBackground();
			Color fg = selected ? Theme.readableOn(bg) : tree.getForeground();
			Color secondary = selected ? Theme.mix(fg, bg, 0.72f) : Theme.muted();
			setBackground(bg);
			iconLabel.setBackground(bg);
			nameLabel.setBackground(bg);
			badgeLabel.setBackground(bg);

			if (!(value instanceof ZkTreeModel.Node))
			{
				nameLabel.setText(String.valueOf(value));
				return this;
			}
			ZkTreeModel.Node node = (ZkTreeModel.Node) value;
			if (node.placeholder)
			{
				iconLabel.setIcon(null);
				nameLabel.setText("Loading…");
				nameLabel.setFont(Theme.uiFont(Font.ITALIC, tree.getFont().getSize()));
				nameLabel.setForeground(Theme.muted());
				badgeLabel.setText("");
				return this;
			}

			NodeSnapshot snap = node.snapshot;
			boolean isFolder = snap == null ? !leaf : snap.numChildren > 0;
			Icon icon;
			if (snap != null && snap.isEphemeral())
			{
				icon = Icons.get("ephemeral");
			}
			else if (isFolder)
			{
				icon = Icons.soft(expanded ? "folder-open" : "folder");
			}
			else
			{
				icon = Icons.soft("node");
			}
			iconLabel.setIcon(icon);
			nameLabel.setText(node.name);
			nameLabel.setFont(tree.getFont());
			nameLabel.setForeground(node.error != null ? Theme.warn() : fg);

			StringBuilder badge = new StringBuilder();
			if (node.error != null)
			{
				badge.append(node.error);
			}
			else if (snap != null)
			{
				if (snap.numChildren > 0)
				{
					badge.append(snap.numChildren);
				}
				if (snap.dataLength > 0)
				{
					if (badge.length() > 0)
					{
						badge.append(" · ");
					}
					badge.append(formatSize(snap.dataLength));
				}
			}
			badgeLabel.setText(badge.toString());
			badgeLabel.setForeground(node.error != null ? Theme.warn() : secondary);
			return this;
		}
	}

	static String formatSize(int bytes)
	{
		if (bytes < 1024)
		{
			return bytes + " B";
		}
		if (bytes < 1024 * 1024)
		{
			return String.format("%.1f KB", bytes / 1024f);
		}
		return String.format("%.1f MB", bytes / (1024f * 1024f));
	}
}