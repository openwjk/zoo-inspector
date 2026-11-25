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
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.event.EventListenerList;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

import com.zooinspector.core.Dubbo;
import com.zooinspector.core.PathIndex;
import com.zooinspector.util.ZkPaths;

/**
 * 搜索结果。两种形态：
 *
 * <ul>
 * <li><b>聚合视图</b>（默认）：一行一个 Dubbo 接口，带 provider/consumer 数量；
 * 点开接口才展开 providers / consumers，再点开分类才看实例。
 * 实例名是 URL 编码长串，这里解码成 host:port + application/version 展示。
 * <li><b>平铺视图</b>：关掉 Group interfaces 后回到原来的逐节点列表。
 * </ul>
 *
 * <p>展开出来的子节点全部来自内存索引，不发网络请求。
 */
public class SearchResultsPanel extends JPanel
{
	public interface Listener
	{
		void hitSelected(String path);

		void hitActivated(String path);

		void escapePressed();
	}

	private static final String CARD_FLAT = "flat";
	private static final String CARD_GROUPED = "grouped";

	private final HitModel model = new HitModel();
	private final JList<PathIndex.Hit> list = new JList<PathIndex.Hit>(model);
	private final JLabel empty = new JLabel("", JLabel.CENTER);
	private final JScrollPane flatScroll;
	private final GroupModel groupModel = new GroupModel();
	private final JTree groupTree = new JTree(groupModel);
	private final JScrollPane groupScroll;
	private final CardLayout cards = new CardLayout();
	private final JPanel cardPanel = new JPanel();

	private Listener listener;
	private String query = "";
	private PathIndex.Mode mode = PathIndex.Mode.CONTAINS;
	private PathIndex index = PathIndex.EMPTY;
	private boolean grouped = true;
	/** 重新搜索后尽量保留原来展开的接口 */
	private final Set<String> expanded = new HashSet<String>();

	public SearchResultsPanel()
	{
		super(new BorderLayout());
		list.setCellRenderer(new HitRenderer());
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFixedCellHeight(30);
		list.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		list.addListSelectionListener(new javax.swing.event.ListSelectionListener()
		{
			@Override
			public void valueChanged(javax.swing.event.ListSelectionEvent e)
			{
				if (!e.getValueIsAdjusting() && listener != null)
				{
					PathIndex.Hit hit = list.getSelectedValue();
					if (hit != null)
					{
						listener.hitSelected(hit.path);
					}
				}
			}
		});
		list.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getClickCount() == 2 && listener != null)
				{
					PathIndex.Hit hit = list.getSelectedValue();
					if (hit != null)
					{
						listener.hitActivated(hit.path);
					}
				}
			}
		});
		list.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ENTER && listener != null)
				{
					PathIndex.Hit hit = list.getSelectedValue();
					if (hit != null)
					{
						listener.hitActivated(hit.path);
					}
				}
				else if (e.getKeyCode() == KeyEvent.VK_ESCAPE && listener != null)
				{
					listener.escapePressed();
				}
			}
		});
		flatScroll = new JScrollPane(list);
		flatScroll.setBorder(BorderFactory.createEmptyBorder());
		flatScroll.setHorizontalScrollBarPolicy(
				javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

		groupTree.setRootVisible(false);
		groupTree.setShowsRootHandles(true);
		groupTree.setRowHeight(0);
		groupTree.setCellRenderer(new GroupRenderer());
		groupTree.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		groupTree.addTreeSelectionListener(new javax.swing.event.TreeSelectionListener()
		{
			@Override
			public void valueChanged(javax.swing.event.TreeSelectionEvent e)
			{
				GNode node = selectedNode();
				if (node != null && listener != null && node.kind != GNode.ROOT)
				{
					listener.hitSelected(node.path);
				}
			}
		});
		groupTree.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getClickCount() != 2 || listener == null)
				{
					return;
				}
				GNode node = selectedNode();
				if (node == null)
				{
					return;
				}
				if (node.expandable())
				{
					toggle(node);
				}
				else
				{
					listener.hitActivated(node.path);
				}
			}
		});
		groupTree.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE && listener != null)
				{
					listener.escapePressed();
				}
				else if (e.getKeyCode() == KeyEvent.VK_ENTER && listener != null)
				{
					GNode node = selectedNode();
					if (node != null && !node.expandable())
					{
						listener.hitActivated(node.path);
					}
				}
			}
		});
		groupTree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener()
		{
			@Override
			public void treeExpanded(javax.swing.event.TreeExpansionEvent event)
			{
				GNode node = (GNode) event.getPath().getLastPathComponent();
				if (node.path != null)
				{
					expanded.add(node.path);
				}
			}

			@Override
			public void treeCollapsed(javax.swing.event.TreeExpansionEvent event)
			{
				GNode node = (GNode) event.getPath().getLastPathComponent();
				if (node.path != null)
				{
					expanded.remove(node.path);
				}
			}
		});
		groupScroll = new JScrollPane(groupTree);
		groupScroll.setBorder(BorderFactory.createEmptyBorder());
		groupScroll.setHorizontalScrollBarPolicy(
				javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

		cardPanel.setLayout(cards);
		cardPanel.add(flatScroll, CARD_FLAT);
		cardPanel.add(groupScroll, CARD_GROUPED);
		empty.setForeground(Theme.muted());
		empty.setBorder(BorderFactory.createEmptyBorder(24, 16, 24, 16));
		add(cardPanel, BorderLayout.CENTER);
	}

	public void setListener(Listener l)
	{
		this.listener = l;
	}

	public void focusList()
	{
		if (grouped)
		{
			groupTree.requestFocusInWindow();
			if (groupTree.getSelectionCount() == 0 && groupModel.root.children != null
					&& !groupModel.root.children.isEmpty())
			{
				groupTree.setSelectionRow(0);
			}
		}
		else
		{
			list.requestFocusInWindow();
			if (list.getSelectedIndex() < 0 && model.getSize() > 0)
			{
				list.setSelectedIndex(0);
			}
		}
	}

	public String selectedPath()
	{
		if (grouped)
		{
			GNode node = selectedNode();
			return node == null || node.kind == GNode.ROOT ? null : node.path;
		}
		PathIndex.Hit hit = list.getSelectedValue();
		return hit == null ? null : hit.path;
	}

	public void selectFirst()
	{
		if (grouped)
		{
			if (groupModel.root.children != null && !groupModel.root.children.isEmpty())
			{
				groupTree.setSelectionRow(0);
			}
		}
		else if (model.getSize() > 0)
		{
			list.setSelectedIndex(0);
			list.ensureIndexIsVisible(0);
		}
	}

	/** 聚合视图里回车 = 点开选中的接口/分类；返回 false 表示没什么可展开 */
	public boolean expandSelected()
	{
		if (!grouped)
		{
			return false;
		}
		GNode node = selectedNode();
		if (node == null)
		{
			selectFirst();
			node = selectedNode();
		}
		if (node != null && node.expandable())
		{
			toggle(node);
			groupTree.requestFocusInWindow();
			return true;
		}
		return false;
	}

	/** 选中并点开第 row 行（自动化验证用） */
	public boolean expandRow(int row)
	{
		if (!grouped || row < 0 || row >= groupTree.getRowCount())
		{
			return false;
		}
		groupTree.setSelectionRow(row);
		GNode node = selectedNode();
		if (node != null && node.expandable())
		{
			toggle(node);
			return true;
		}
		return false;
	}

	private GNode selectedNode()
	{
		TreePath tp = groupTree.getSelectionPath();
		return tp == null ? null : (GNode) tp.getLastPathComponent();
	}

	private void toggle(GNode node)
	{
		TreePath tp = groupModel.pathTo(node);
		if (groupTree.isExpanded(tp))
		{
			groupTree.collapsePath(tp);
		}
		else
		{
			groupModel.ensureChildren(node);
			groupTree.expandPath(tp);
		}
	}

	public void showResult(PathIndex.Result result, String query, PathIndex.Mode mode)
	{
		showResult(result, query, mode, PathIndex.EMPTY, false);
	}

	public void showResult(PathIndex.Result result, String query, PathIndex.Mode mode,
			PathIndex index, boolean grouped)
	{
		this.query = query == null ? "" : query;
		this.mode = mode;
		this.index = index == null ? PathIndex.EMPTY : index;
		this.grouped = grouped;
		if (result == null)
		{
			model.set(Collections.<PathIndex.Hit> emptyList());
			groupModel.set(Collections.<PathIndex.Hit> emptyList());
			return;
		}
		if (result.error != null)
		{
			model.set(Collections.<PathIndex.Hit> emptyList());
			groupModel.set(Collections.<PathIndex.Hit> emptyList());
			showMessage(result.error);
			return;
		}
		if (result.hits.isEmpty())
		{
			model.set(Collections.<PathIndex.Hit> emptyList());
			groupModel.set(Collections.<PathIndex.Hit> emptyList());
			showMessage("No matches");
			return;
		}
		remove(empty);
		add(cardPanel, BorderLayout.CENTER);
		if (grouped)
		{
			groupModel.set(result.hits);
			cards.show(cardPanel, CARD_GROUPED);
			restoreExpansion();
			if (groupTree.getSelectionCount() == 0)
			{
				groupTree.setSelectionRow(0);
			}
		}
		else
		{
			list.setFixedCellHeight(mode == PathIndex.Mode.DATA ? 44 : 30);
			model.set(result.hits);
			cards.show(cardPanel, CARD_FLAT);
			if (list.getSelectedIndex() < 0)
			{
				list.setSelectedIndex(0);
			}
		}
		revalidate();
		repaint();
	}

	private void restoreExpansion()
	{
		if (expanded.isEmpty() || groupModel.root.children == null)
		{
			return;
		}
		for (GNode node : groupModel.root.children)
		{
			if (expanded.contains(node.path))
			{
				groupModel.ensureChildren(node);
				groupTree.expandPath(groupModel.pathTo(node));
				for (GNode child : node.children)
				{
					if (expanded.contains(child.path))
					{
						groupModel.ensureChildren(child);
						groupTree.expandPath(groupModel.pathTo(child));
					}
				}
			}
		}
	}

	private void showMessage(String message)
	{
		empty.setText("<html><div style='text-align:center'>" + message + "</div></html>");
		remove(cardPanel);
		add(empty, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	// ---------------- 平铺视图 ----------------

	private static final class HitModel extends AbstractListModel<PathIndex.Hit>
	{
		private List<PathIndex.Hit> hits = Collections.emptyList();

		void set(List<PathIndex.Hit> newHits)
		{
			int old = hits.size();
			hits = newHits;
			// 一次 contentsChanged 比 remove+add 两个区间事件便宜得多
			fireContentsChanged(this, 0, Math.max(old, hits.size()));
		}

		@Override
		public int getSize()
		{
			return hits.size();
		}

		@Override
		public PathIndex.Hit getElementAt(int index)
		{
			return hits.get(index);
		}
	}

	/** 平铺行：节点名加粗 + 命中片段着色，父路径淡色，右侧 badge */
	private final class HitRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean isSelected, boolean cellHasFocus)
		{
			JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index,
					isSelected, cellHasFocus);
			if (!(value instanceof PathIndex.Hit))
			{
				return label;
			}
			PathIndex.Hit hit = (PathIndex.Hit) value;
			label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
			label.setIcon(hit.numChildren > 0 ? Icons.soft("folder")
					: hit.ephemeral ? Icons.get("ephemeral") : Icons.soft("node"));
			label.setIconTextGap(8);

			Color bg = label.getBackground();
			Color readable = isSelected ? Theme.readableOn(bg) : Theme.foreground();
			if (isSelected)
			{
				label.setForeground(readable);
			}
			Color muted = isSelected ? Theme.mix(readable, bg, 0.74f) : Theme.muted();
			Color accent = isSelected ? readable : Theme.accent();
			String display = Dubbo.isInstanceName(hit.name) ? Dubbo.instanceLabel(hit.name)
					: hit.name;
			StringBuilder html = new StringBuilder("<html>");
			html.append("<b>").append(highlight(display, query, accent)).append("</b>");
			String parent = hit.parent();
			if (!parent.isEmpty())
			{
				html.append("  <span style='color:").append(hex(muted)).append("'>")
						.append(esc(parent)).append("</span>");
			}
			String badge = badge(hit);
			if (!badge.isEmpty())
			{
				html.append("  <span style='color:").append(hex(muted)).append("'>· ")
						.append(badge).append("</span>");
			}
			if (hit.snippet != null)
			{
				html.append("<br><span style='font-family:monospace;color:").append(hex(muted))
						.append("'>").append(highlight(hit.snippet, query, accent)).append("</span>");
			}
			html.append("</html>");
			label.setText(html.toString());
			label.setToolTipText(Dubbo.isInstanceName(hit.name) ? Dubbo.decode(hit.name) : hit.path);
			return label;
		}

		private String badge(PathIndex.Hit hit)
		{
			StringBuilder sb = new StringBuilder();
			if (hit.numChildren > 0)
			{
				sb.append(hit.numChildren).append(hit.numChildren == 1 ? " child" : " children");
			}
			if (hit.dataLength > 0)
			{
				if (sb.length() > 0)
				{
					sb.append(", ");
				}
				sb.append(TreePanel.formatSize(hit.dataLength));
			}
			if (hit.ephemeral)
			{
				if (sb.length() > 0)
				{
					sb.append(", ");
				}
				sb.append("ephemeral");
			}
			return sb.toString();
		}
	}

	// ---------------- 聚合视图 ----------------

	private static final class GNode
	{
		static final int ROOT = 0;
		static final int INTERFACE = 1;
		static final int PLAIN = 2;
		static final int CATEGORY = 3;
		static final int INSTANCE = 4;

		final int kind;
		final String path;
		final GNode parent;
		String title;
		String secondary;
		String badge;
		/** 第二行：接口的 provider/consumer 数量、实例的 application/version */
		String secondLine;
		String tooltip;
		int childCount;
		boolean ephemeral;
		List<GNode> children;

		GNode(int kind, String path, GNode parent)
		{
			this.kind = kind;
			this.path = path;
			this.parent = parent;
		}

		boolean expandable()
		{
			return kind == INTERFACE || kind == CATEGORY || (kind == PLAIN && childCount > 0);
		}

		@Override
		public String toString()
		{
			return title == null ? String.valueOf(path) : title;
		}
	}

	/** 结果树模型：全部读内存索引，展开零网络 */
	private final class GroupModel implements TreeModel
	{
		private final GNode root = new GNode(GNode.ROOT, null, null);
		private final EventListenerList listeners = new EventListenerList();

		void set(List<PathIndex.Hit> hits)
		{
			List<GNode> rows = new ArrayList<GNode>(hits.size());
			for (PathIndex.Hit hit : hits)
			{
				GNode node;
				if (hit.interfaceNode)
				{
					node = new GNode(GNode.INTERFACE, hit.path, root);
					String fqn = hit.name;
					int dot = fqn.lastIndexOf('.');
					node.title = dot > 0 ? fqn.substring(dot + 1) : fqn;
					node.secondary = dot > 0 ? fqn.substring(0, dot) : hit.parent();
					StringBuilder sb = new StringBuilder();
					if (hit.providers >= 0)
					{
						sb.append(hit.providers).append(" providers");
					}
					if (hit.consumers >= 0)
					{
						if (sb.length() > 0)
						{
							sb.append(" · ");
						}
						sb.append(hit.consumers).append(" consumers");
					}
					node.secondLine = sb.toString();
					node.tooltip = hit.path;
					node.childCount = Dubbo.categoriesOf(index.categoriesOf(hit.path)).length;
				}
				else
				{
					node = new GNode(GNode.PLAIN, hit.path, root);
					node.title = Dubbo.isInstanceName(hit.name) ? Dubbo.instanceLabel(hit.name)
							: hit.name;
					node.secondary = hit.parent();
					node.secondLine = hit.snippet;
					node.badge = hit.snippet != null ? ""
							: (hit.numChildren > 0 ? hit.numChildren + " children" : "");
					node.tooltip = hit.path;
					node.childCount = hit.numChildren;
				}
				rows.add(node);
			}
			root.children = rows;
			TreeModelEvent e = new TreeModelEvent(this, new TreePath(root));
			for (TreeModelListener l : listeners.getListeners(TreeModelListener.class))
			{
				l.treeStructureChanged(e);
			}
		}

		void ensureChildren(GNode node)
		{
			if (node.children != null || !node.expandable())
			{
				return;
			}
			List<GNode> kids = new ArrayList<GNode>();
			if (node.kind == GNode.INTERFACE)
			{
				for (String category : Dubbo.categoriesOf(index.categoriesOf(node.path)))
				{
					String catPath = ZkPaths.join(node.path, category);
					GNode kid = new GNode(GNode.CATEGORY, catPath, node);
					kid.title = category;
					kid.childCount = Math.max(0, index.childCount(catPath));
					kid.badge = String.valueOf(kid.childCount);
					kid.tooltip = catPath;
					kids.add(kid);
				}
			}
			else
			{
				for (String childPath : index.childrenOf(node.path))
				{
					String name = ZkPaths.name(childPath);
					boolean instance = node.kind == GNode.CATEGORY || Dubbo.isInstanceName(name);
					GNode kid = new GNode(instance ? GNode.INSTANCE : GNode.PLAIN, childPath, node);
					if (instance)
					{
						kid.title = Dubbo.instanceLabel(name);
						kid.secondLine = Dubbo.instanceDetail(name);
						kid.tooltip = Dubbo.decode(name);
					}
					else
					{
						kid.title = name;
						kid.tooltip = childPath;
					}
					kid.childCount = Math.max(0, index.childCount(childPath));
					kid.ephemeral = index.isEphemeralPath(childPath);
					if (!instance && kid.childCount > 0)
					{
						kid.badge = String.valueOf(kid.childCount);
					}
					kids.add(kid);
				}
			}
			node.children = kids;
			// 不在这里发事件：ensureChildren 会在 JTree 询问 getChildCount/getChild 时
			// 被重入调用，此时改模型再发事件会打乱正在进行的布局。
		}

		TreePath pathTo(GNode node)
		{
			List<GNode> chain = new ArrayList<GNode>();
			GNode cur = node;
			while (cur != null)
			{
				chain.add(0, cur);
				cur = cur.parent;
			}
			return new TreePath(chain.toArray());
		}

		@Override
		public Object getRoot()
		{
			return root;
		}

		@Override
		public Object getChild(Object parent, int index)
		{
			GNode node = (GNode) parent;
			ensureChildren(node);
			return node.children != null && index < node.children.size() ? node.children.get(index)
					: null;
		}

		@Override
		public int getChildCount(Object parent)
		{
			GNode node = (GNode) parent;
			if (node.kind == GNode.ROOT)
			{
				return node.children == null ? 0 : node.children.size();
			}
			ensureChildren(node);
			return node.children == null ? 0 : node.children.size();
		}

		@Override
		public boolean isLeaf(Object node)
		{
			GNode n = (GNode) node;
			if (n.kind == GNode.ROOT)
			{
				return false;
			}
			if (n.children != null)
			{
				return n.children.isEmpty();
			}
			return !n.expandable();
		}

		@Override
		public int getIndexOfChild(Object parent, Object child)
		{
			GNode p = (GNode) parent;
			if (p.children == null)
			{
				return -1;
			}
			return p.children.indexOf(child);
		}

		@Override
		public void valueForPathChanged(TreePath path, Object newValue)
		{
		}

		@Override
		public void addTreeModelListener(TreeModelListener l)
		{
			listeners.add(TreeModelListener.class, l);
		}

		@Override
		public void removeTreeModelListener(TreeModelListener l)
		{
			listeners.remove(TreeModelListener.class, l);
		}
	}

	/** 聚合行渲染：接口简单名加粗 + 包名淡色 + provider/consumer 数量 */
	private final class GroupRenderer extends JPanel implements javax.swing.tree.TreeCellRenderer
	{
		private final JLabel iconLabel = new JLabel();
		private final JLabel text = new JLabel();

		GroupRenderer()
		{
			super(new BorderLayout(8, 0));
			setOpaque(true);
			add(iconLabel, BorderLayout.WEST);
			add(text, BorderLayout.CENTER);
			setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 8));
		}

		@Override
		public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
				boolean expanded, boolean leaf, int row, boolean hasFocus)
		{
			GNode node = (GNode) value;
			Color bg = selected ? Theme.selectionBackground("Tree", tree.getBackground(),
					tree.hasFocus()) : tree.getBackground();
			Color readable = selected ? Theme.readableOn(bg) : tree.getForeground();
			Color muted = selected ? Theme.mix(readable, bg, 0.74f) : Theme.muted();
			Color accent = selected ? readable : Theme.accent();
			setBackground(bg);
			iconLabel.setBackground(bg);
			text.setBackground(bg);
			text.setForeground(readable);

			Icon icon;
			switch (node.kind)
			{
				case GNode.INTERFACE:
					icon = Icons.soft(expanded ? "folder-open" : "folder");
					break;
				case GNode.CATEGORY:
					icon = Icons.get("children");
					break;
				case GNode.INSTANCE:
					icon = node.ephemeral ? Icons.get("ephemeral") : Icons.soft("node");
					break;
				default:
					icon = Icons.soft(node.childCount > 0 ? (expanded ? "folder-open" : "folder")
							: "node");
					break;
			}
			iconLabel.setIcon(icon);

			StringBuilder html = new StringBuilder("<html>");
			html.append("<b>").append(highlight(node.title, query, accent)).append("</b>");
			if (node.secondary != null && !node.secondary.isEmpty())
			{
				html.append("  <span style='color:").append(hex(muted)).append("'>")
						.append(esc(node.secondary)).append("</span>");
			}
			if (node.badge != null && !node.badge.isEmpty())
			{
				html.append("  <span style='color:").append(hex(muted)).append("'>· ")
						.append(esc(node.badge)).append("</span>");
			}
			if (node.secondLine != null && !node.secondLine.isEmpty())
			{
				html.append("<br><span style='color:").append(hex(muted)).append("'>")
						.append(esc(node.secondLine)).append("</span>");
			}
			html.append("</html>");
			text.setText(html.toString());
			text.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
			setToolTipText(node.tooltip);
			return this;
		}
	}

	// ---------------- 公共小工具 ----------------

	private static String highlight(String text, String query, Color accent)
	{
		if (text == null)
		{
			return "";
		}
		if (query == null || query.isEmpty())
		{
			return esc(text);
		}
		String lowerText = text.toLowerCase(Locale.ROOT);
		String lowerQuery = query.toLowerCase(Locale.ROOT);
		int at = lowerText.indexOf(lowerQuery);
		if (at < 0)
		{
			return esc(text);
		}
		return esc(text.substring(0, at)) + "<span style='color:" + hex(accent) + "'>"
				+ esc(text.substring(at, at + query.length())) + "</span>"
				+ esc(text.substring(at + query.length()));
	}

	private static String esc(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String hex(Color c)
	{
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}
}