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
package com.zooinspector.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.event.EventListenerList;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

import com.zooinspector.util.Edt;
import com.zooinspector.util.ZkPaths;

/**
 * 带快照缓存的树模型 —— 本次重写最关键的一处。
 *
 * <p>老版 {@code ZooInspectorTreeViewer.ZooInspectorTreeNode} 的每次
 * {@code getChildCount()} / {@code isLeaf()} / {@code getChildAt()} 都会同步发一次
 * exists/getChildren 请求，而且发生在 EDT 上，渲染一屏就是几十次串行往返。
 *
 * <p>这里所有 TreeModel 方法都只读内存快照，网络加载全部异步：
 * 展开时发一次 {@code getChildren(path,false,stat)}（子节点 + stat 一次拿全），
 * 再用异步 exists 流水线补齐子节点 stat，合并成一批 UI 更新。
 */
public class ZkTreeModel implements TreeModel
{
	public static final int UNLOADED = 0;
	public static final int LOADING = 1;
	public static final int LOADED = 2;
	public static final int ERROR = 3;

	public static final class Node
	{
		public final String path;
		public final String name;
		public final Node parent;
		public final boolean placeholder;
		public volatile NodeSnapshot snapshot;
		public volatile List<Node> children;
		public volatile int state = UNLOADED;
		public volatile String error;
		public volatile boolean watched;

		Node(String path, Node parent, boolean placeholder)
		{
			this.path = path;
			this.name = placeholder ? "Loading…" : ZkPaths.name(path);
			this.parent = parent;
			this.placeholder = placeholder;
		}

		public boolean isLoaded()
		{
			return state == LOADED;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	private final ZkConnection conn;
	private final EventListenerList listeners = new EventListenerList();

	/** 与子节点名排序保持一致的自然序 */
	private static final java.util.Comparator<Node> NODE_ORDER = new java.util.Comparator<Node>()
	{
		@Override
		public int compare(Node a, Node b)
		{
			return ZkPaths.NATURAL.compare(a.name, b.name);
		}
	};
	private final Map<String, Node> byPath = new ConcurrentHashMap<String, Node>();
	private final Node root;
	private final Node loadingPlaceholder;

	/** 子节点 stat 补齐后的合并刷新，避免每个回调都触发一次重绘 */
	private final Set<Node> dirty = Collections.newSetFromMap(new ConcurrentHashMap<Node, Boolean>());
	private final AtomicBoolean flushScheduled = new AtomicBoolean();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
			Edt.named("tree-flush"));

	public ZkTreeModel(ZkConnection conn)
	{
		this.conn = conn;
		this.root = new Node(ZkPaths.ROOT, null, false);
		this.loadingPlaceholder = new Node("", null, true);
		byPath.put(ZkPaths.ROOT, root);
	}

	public Node root()
	{
		return root;
	}

	public Node nodeFor(String path)
	{
		return byPath.get(ZkPaths.normalize(path));
	}

	public void clear()
	{
		byPath.clear();
		root.children = null;
		root.snapshot = null;
		root.state = UNLOADED;
		root.error = null;
		byPath.put(ZkPaths.ROOT, root);
		Edt.run(new Runnable()
		{
			@Override
			public void run()
			{
				fireStructureChanged(root);
			}
		});
	}

	// ---------------- TreeModel：全部只读内存 ----------------

	@Override
	public Object getRoot()
	{
		return root;
	}

	@Override
	public Object getChild(Object parent, int index)
	{
		Node n = (Node) parent;
		List<Node> kids = n.children;
		if (kids != null)
		{
			return index >= 0 && index < kids.size() ? kids.get(index) : loadingPlaceholder;
		}
		return loadingPlaceholder;
	}

	@Override
	public int getChildCount(Object parent)
	{
		Node n = (Node) parent;
		if (n.placeholder)
		{
			return 0;
		}
		List<Node> kids = n.children;
		if (kids != null)
		{
			return kids.size();
		}
		NodeSnapshot snap = n.snapshot;
		if (snap != null)
		{
			return snap.numChildren > 0 ? 1 : 0;
		}
		// stat 还没补齐：先当作可展开，展开时再真正加载
		return n.state == ERROR ? 0 : 1;
	}

	@Override
	public boolean isLeaf(Object node)
	{
		Node n = (Node) node;
		if (n.placeholder)
		{
			return true;
		}
		List<Node> kids = n.children;
		if (kids != null)
		{
			return kids.isEmpty();
		}
		NodeSnapshot snap = n.snapshot;
		return snap != null && snap.numChildren == 0;
	}

	@Override
	public int getIndexOfChild(Object parent, Object child)
	{
		Node p = (Node) parent;
		Node c = (Node) child;
		List<Node> kids = p.children;
		if (kids == null || c.placeholder)
		{
			return 0;
		}
		for (int i = 0; i < kids.size(); i++)
		{
			if (kids.get(i).path.equals(c.path))
			{
				return i;
			}
		}
		return -1;
	}

	@Override
	public void valueForPathChanged(TreePath path, Object newValue)
	{
		// 树不可就地编辑
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

	// ---------------- 异步加载 ----------------

	public void ensureLoaded(final Node node)
	{
		ensureLoaded(node, null);
	}

	/** 加载完成后在 EDT 上执行 onDone（已加载则立即执行） */
	public void ensureLoaded(final Node node, final Runnable onDone)
	{
		if (node == null || node.placeholder)
		{
			runDone(onDone);
			return;
		}
		synchronized (node)
		{
			if (node.state == LOADED)
			{
				runDone(onDone);
				return;
			}
			if (node.state == LOADING)
			{
				return;
			}
			node.state = LOADING;
			node.error = null;
		}
		boolean issued = conn.getChildrenAsync(node.path, new ZkConnection.ChildrenCallback()
		{
			@Override
			public void done(String path, List<String> names, NodeSnapshot snapshot,
					Throwable error)
			{
				if (error != null || names == null)
				{
					node.state = ERROR;
					node.error = error == null ? "unknown error" : describe(error);
					node.children = Collections.emptyList();
					Edt.run(new Runnable()
					{
						@Override
						public void run()
						{
							fireStructureChanged(node);
							runDone(onDone);
						}
					});
					return;
				}
				node.snapshot = snapshot;
				final List<Node> kids = new ArrayList<Node>(names.size());
				for (String name : names)
				{
					String childPath = ZkPaths.join(path, name);
					Node child = byPath.get(childPath);
					if (child == null)
					{
						child = new Node(childPath, node, false);
						byPath.put(childPath, child);
					}
					kids.add(child);
				}
				node.children = Collections.unmodifiableList(kids);
				node.state = LOADED;
				Edt.run(new Runnable()
				{
					@Override
					public void run()
					{
						fireStructureChanged(node);
						runDone(onDone);
					}
				});
				prefetchStats(kids);
			}
		});
		if (!issued)
		{
			node.state = ERROR;
			node.error = "not connected";
			Edt.run(new Runnable()
			{
				@Override
				public void run()
				{
					fireStructureChanged(node);
					runDone(onDone);
				}
			});
		}
	}

	/** 异步 exists 流水线补齐子节点 stat，让叶子/文件夹图标与 badge 立刻正确 */
	private void prefetchStats(List<Node> kids)
	{
		for (final Node child : kids)
		{
			if (child.snapshot != null)
			{
				continue;
			}
			conn.existsAsync(child.path, new ZkConnection.SnapshotCallback()
			{
				@Override
				public void done(String path, NodeSnapshot snapshot, Throwable error)
				{
					if (snapshot != null)
					{
						child.snapshot = snapshot;
						markDirty(child);
					}
					else if (error == null)
					{
						// 节点在 listing 之后被删掉了
						child.error = "deleted";
						markDirty(child);
					}
				}
			});
		}
	}

	private void markDirty(Node n)
	{
		dirty.add(n);
		if (flushScheduled.compareAndSet(false, true))
		{
			scheduler.schedule(new Runnable()
			{
				@Override
				public void run()
				{
					Edt.run(new Runnable()
					{
						@Override
						public void run()
						{
							flushDirty();
						}
					});
				}
			}, 60, TimeUnit.MILLISECONDS);
		}
	}

	private void flushDirty()
	{
		flushScheduled.set(false);
		if (dirty.isEmpty())
		{
			return;
		}
		List<Node> batch = new ArrayList<Node>(dirty);
		dirty.clear();
		Map<Node, List<Node>> byParent = new LinkedHashMap<Node, List<Node>>();
		for (Node n : batch)
		{
			Node p = n.parent == null ? root : n.parent;
			List<Node> list = byParent.get(p);
			if (list == null)
			{
				list = new ArrayList<Node>();
				byParent.put(p, list);
			}
			list.add(n);
		}
		for (Map.Entry<Node, List<Node>> e : byParent.entrySet())
		{
			fireNodesChanged(e.getKey(), e.getValue());
		}
	}

	/** 强制重新加载某个节点（刷新子树） */
	public void reload(Node node)
	{
		if (node == null)
		{
			return;
		}
		synchronized (node)
		{
			node.state = UNLOADED;
			node.children = null;
		}
		ensureLoaded(node);
	}

	/** 应用内新建节点后就地打补丁，不必全量刷新 */
	public void patchCreated(String parentPath, String childPath, NodeSnapshot snapshot)
	{
		final Node parent = byPath.get(ZkPaths.normalize(parentPath));
		if (parent == null)
		{
			return;
		}
		final Node child = new Node(ZkPaths.normalize(childPath), parent, false);
		child.snapshot = snapshot;
		byPath.put(child.path, child);
		List<Node> kids = parent.children;
		List<Node> updated = new ArrayList<Node>();
		if (kids != null)
		{
			updated.addAll(kids);
		}
		boolean found = false;
		for (int i = 0; i < updated.size(); i++)
		{
			if (updated.get(i).path.equals(child.path))
			{
				updated.set(i, child);
				found = true;
				break;
			}
		}
		if (!found)
		{
			updated.add(child);
		}
		Collections.sort(updated, NODE_ORDER);
		parent.children = Collections.unmodifiableList(updated);
		Edt.run(new Runnable()
		{
			@Override
			public void run()
			{
				fireStructureChanged(parent);
			}
		});
	}

	/** 应用内删除节点后摘掉整棵子树 */
	public void patchDeleted(String path)
	{
		String p = ZkPaths.normalize(path);
		final Node removed = byPath.remove(p);
		List<String> doomed = new ArrayList<String>();
		for (String key : byPath.keySet())
		{
			if (key.startsWith(p + "/"))
			{
				doomed.add(key);
			}
		}
		for (String key : doomed)
		{
			byPath.remove(key);
		}
		if (removed == null || removed.parent == null)
		{
			return;
		}
		final Node parent = removed.parent;
		List<Node> kids = parent.children;
		if (kids != null)
		{
			List<Node> updated = new ArrayList<Node>(kids);
			for (int i = updated.size() - 1; i >= 0; i--)
			{
				if (updated.get(i).path.equals(p))
				{
					updated.remove(i);
				}
			}
			parent.children = Collections.unmodifiableList(updated);
		}
		Edt.run(new Runnable()
		{
			@Override
			public void run()
			{
				fireStructureChanged(parent);
			}
		});
	}

	/** stat 变化（保存 data 等）后刷新单个节点 */
	public void patchSnapshot(String path, NodeSnapshot snapshot)
	{
		Node n = byPath.get(ZkPaths.normalize(path));
		if (n != null && snapshot != null)
		{
			n.snapshot = snapshot;
			markDirty(n);
		}
	}

	private void runDone(Runnable r)
	{
		if (r != null)
		{
			Edt.run(r);
		}
	}

	static String describe(Throwable t)
	{
		if (t instanceof org.apache.zookeeper.KeeperException.NoNodeException)
		{
			return "node no longer exists";
		}
		if (t instanceof org.apache.zookeeper.KeeperException.NoAuthException)
		{
			return "not authorized (check ACL / auth)";
		}
		if (t instanceof org.apache.zookeeper.KeeperException.ConnectionLossException)
		{
			return "connection lost";
		}
		String m = t.getMessage();
		return m == null ? t.getClass().getSimpleName() : m;
	}

	// ---------------- 事件 ----------------

	private TreePath pathTo(Node n)
	{
		List<Node> chain = new ArrayList<Node>();
		Node cur = n;
		while (cur != null)
		{
			chain.add(0, cur);
			cur = cur.parent;
		}
		return new TreePath(chain.toArray());
	}

	private void fireStructureChanged(Node n)
	{
		TreeModelEvent e = new TreeModelEvent(this, pathTo(n));
		for (TreeModelListener l : listeners.getListeners(TreeModelListener.class))
		{
			l.treeStructureChanged(e);
		}
	}

	private void fireNodesChanged(Node parent, List<Node> changed)
	{
		int[] indices = new int[changed.size()];
		Object[] kids = changed.toArray();
		List<Node> current = parent.children;
		for (int i = 0; i < changed.size(); i++)
		{
			int idx = 0;
			if (current != null)
			{
				for (int j = 0; j < current.size(); j++)
				{
					if (current.get(j).path.equals(changed.get(i).path))
					{
						idx = j;
						break;
					}
				}
			}
			indices[i] = idx;
		}
		TreeModelEvent e = new TreeModelEvent(this, pathTo(parent), indices, kids);
		for (TreeModelListener l : listeners.getListeners(TreeModelListener.class))
		{
			l.treeNodesChanged(e);
		}
	}
}