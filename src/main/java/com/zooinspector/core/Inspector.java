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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;

import com.zooinspector.util.Edt;
import com.zooinspector.util.ZkPaths;

/**
 * 应用控制器：把连接、缓存树、搜索索引串起来，向 UI 暴露高层动作。
 * 所有 ZooKeeper 调用都在后台线程发起，回调统一回到 EDT。
 */
public class Inspector
{
	/** error 为 null 表示成功 */
	public interface Done
	{
		void done(String error);
	}

	public interface IndexListener
	{
		void indexProgress(int indexed, int pending, String phase);

		void indexChanged(PathIndex index, long elapsedMs, String note);
	}

	private final ZkConnection conn = new ZkConnection();
	private final ZkTreeModel tree = new ZkTreeModel(conn);
	private final SearchEngine search = new SearchEngine();
	private final List<IndexListener> indexListeners = new CopyOnWriteArrayList<IndexListener>();

	private volatile Settings settings = ConfigStore.loadSettings();
	private volatile List<ConnectProfile> profiles = ConfigStore.loadProfiles();
	private volatile PathIndex index = PathIndex.EMPTY;
	private volatile IndexBuilder building;
	private volatile boolean indexStale;
	private volatile boolean dataIndexRequested;

	public Inspector()
	{
		search.setLimit(settings.resultLimit);
		search.setGroupInterfaces(settings.groupInterfaces);
	}

	public ZkConnection connection()
	{
		return conn;
	}

	public ZkTreeModel tree()
	{
		return tree;
	}

	public SearchEngine search()
	{
		return search;
	}

	public Settings settings()
	{
		return settings;
	}

	public List<ConnectProfile> profiles()
	{
		return profiles;
	}

	public PathIndex index()
	{
		return index;
	}

	public boolean isIndexStale()
	{
		return indexStale;
	}

	public boolean isIndexing()
	{
		IndexBuilder b = building;
		return b != null && !b.isFinished();
	}

	public boolean hasDataIndex()
	{
		return index.dataIndexed;
	}

	public void addIndexListener(IndexListener l)
	{
		indexListeners.add(l);
	}

	public void saveSettings()
	{
		ConfigStore.saveSettings(settings);
		search.setLimit(settings.resultLimit);
		search.setGroupInterfaces(settings.groupInterfaces);
	}

	public void saveProfiles()
	{
		ConfigStore.saveProfiles(profiles);
	}

	public void setProfiles(List<ConnectProfile> list)
	{
		this.profiles = new ArrayList<ConnectProfile>(list);
		saveProfiles();
	}

	// ---------------- 连接 ----------------

	public void connect(final ConnectProfile profile, final Done done)
	{
		Edt.background(new Callable<String>()
		{
			@Override
			public String call()
			{
				try
				{
					conn.connect(profile);
					return null;
				}
				catch (Exception e)
				{
					return message(e);
				}
			}
		}, new Edt.Callback<String>()
		{
			@Override
			public void accept(String error)
			{
				if (error == null)
				{
					settings.lastProfile = profile.displayName();
					saveSettings();
					index = PathIndex.EMPTY;
					indexStale = false;
					search.setIndex(index);
					tree.clear();
					tree.ensureLoaded(tree.root());
					notifyIndexChanged(0, null);
					if (settings.indexOnConnect)
					{
						rebuildIndex(dataIndexRequested);
					}
				}
				done.done(error);
			}
		}, null);
	}

	public void disconnect(final Done done)
	{
		cancelIndex();
		Edt.background(new Callable<String>()
		{
			@Override
			public String call()
			{
				try
				{
					conn.close();
					return null;
				}
				catch (Exception e)
				{
					return message(e);
				}
			}
		}, new Edt.Callback<String>()
		{
			@Override
			public void accept(String error)
			{
				tree.clear();
				index = PathIndex.EMPTY;
				search.setIndex(index);
				notifyIndexChanged(0, null);
				if (done != null)
				{
					done.done(error);
				}
			}
		}, null);
	}

	// ---------------- 索引 ----------------

	public void rebuildIndex(final boolean withData)
	{
		if (!conn.isUsable())
		{
			return;
		}
		cancelIndex();
		this.dataIndexRequested = withData;
		IndexBuilder.Options opts = settings.toIndexOptions(withData);
		opts.rootPath = ZkPaths.ROOT;
		final IndexBuilder builder = new IndexBuilder(conn, opts);
		building = builder;
		builder.start(new IndexBuilder.Listener()
		{
			@Override
			public void progress(int indexed, int pending, String phase)
			{
				for (IndexListener l : indexListeners)
				{
					l.indexProgress(indexed, pending, phase);
				}
			}

			@Override
			public void finished(PathIndex result, long elapsedMs, String note)
			{
				if (result != null)
				{
					index = result;
					indexStale = false;
					search.setIndex(result);
				}
				notifyIndexChanged(elapsedMs, note);
			}
		});
	}

	/** watch 到某个子树变化时只重爬那一块，然后单趟合并进索引 */
	public void reindexSubtree(final String path)
	{
		if (!conn.isUsable() || isIndexing() || index.count == 0)
		{
			indexStale = true;
			return;
		}
		IndexBuilder.Options opts = settings.toIndexOptions(index.dataIndexed);
		opts.rootPath = ZkPaths.normalize(path);
		opts.maxNodes = Math.min(opts.maxNodes, 20000);
		final IndexBuilder builder = new IndexBuilder(conn, opts);
		builder.start(new IndexBuilder.Listener()
		{
			@Override
			public void progress(int indexed, int pending, String phase)
			{
				// 局部刷新不打扰状态栏
			}

			@Override
			public void finished(PathIndex result, long elapsedMs, String note)
			{
				if (result != null && result.count > 0)
				{
					index = index.replaceSubtree(path, result);
					search.setIndex(index);
					notifyIndexChanged(elapsedMs, null);
				}
			}
		});
	}

	public void cancelIndex()
	{
		IndexBuilder b = building;
		if (b != null && !b.isFinished())
		{
			b.cancel();
		}
	}

	private void notifyIndexChanged(long elapsedMs, String note)
	{
		PathIndex snapshot = index;
		for (IndexListener l : indexListeners)
		{
			l.indexChanged(snapshot, elapsedMs, note);
		}
	}

	// ---------------- 节点操作 ----------------

	public void createNode(final String parent, final String name, final byte[] data,
			final CreateMode mode, final List<ACL> acls, final Done done)
	{
		final String childPath = ZkPaths.join(parent, name);
		Edt.background(new Callable<String>()
		{
			@Override
			public String call()
			{
				try
				{
					String created = conn.create(childPath, data == null ? new byte[0] : data,
							acls == null || acls.isEmpty() ? ZooDefs.Ids.OPEN_ACL_UNSAFE : acls,
							mode);
					NodeSnapshot snap = conn.exists(created);
					final String actual = created;
					final NodeSnapshot finalSnap = snap;
					Edt.run(new Runnable()
					{
						@Override
						public void run()
						{
							tree.patchCreated(ZkPaths.parent(actual), actual, finalSnap);
							patchIndexAdd(actual, finalSnap);
						}
					});
					return null;
				}
				catch (Exception e)
				{
					return message(e);
				}
			}
		}, new Edt.Callback<String>()
		{
			@Override
			public void accept(String error)
			{
				done.done(error);
			}
		}, null);
	}

	public void saveData(final String path, final byte[] data, final int expectedVersion,
			final Done done)
	{
		Edt.background(new Callable<String>()
		{
			@Override
			public String call()
			{
				try
				{
					conn.setData(path, data, expectedVersion);
					final NodeSnapshot snap = conn.exists(path);
					Edt.run(new Runnable()
					{
						@Override
						public void run()
						{
							tree.patchSnapshot(path, snap);
							patchIndexAdd(path, snap);
						}
					});
					return null;
				}
				catch (org.apache.zookeeper.KeeperException.BadVersionException e)
				{
					return "Someone else changed this node (version conflict). Reload and try again.";
				}
				catch (Exception e)
				{
					return message(e);
				}
			}
		}, new Edt.Callback<String>()
		{
			@Override
			public void accept(String error)
			{
				done.done(error);
			}
		}, null);
	}

	public void deleteRecursive(final String path, final Done done)
	{
		Edt.background(new Callable<String>()
		{
			@Override
			public String call()
			{
				try
				{
					conn.deleteRecursive(path, new AtomicBoolean(false));
					Edt.run(new Runnable()
					{
						@Override
						public void run()
						{
							tree.patchDeleted(path);
							index = index.without(path);
							search.setIndex(index);
							notifyIndexChanged(0, null);
						}
					});
					return null;
				}
				catch (Exception e)
				{
					return message(e);
				}
			}
		}, new Edt.Callback<String>()
		{
			@Override
			public void accept(String error)
			{
				done.done(error);
			}
		}, null);
	}

	public void saveAcl(final String path, final List<ACL> acls, final int aversion,
			final Done done)
	{
		Edt.background(new Callable<String>()
		{
			@Override
			public String call()
			{
				try
				{
					conn.setAcl(path, acls, aversion);
					final NodeSnapshot snap = conn.exists(path);
					Edt.run(new Runnable()
					{
						@Override
						public void run()
						{
							tree.patchSnapshot(path, snap);
						}
					});
					return null;
				}
				catch (org.apache.zookeeper.KeeperException.BadVersionException e)
				{
					return "ACL version conflict — reload the node and try again.";
				}
				catch (Exception e)
				{
					return message(e);
				}
			}
		}, new Edt.Callback<String>()
		{
			@Override
			public void accept(String error)
			{
				done.done(error);
			}
		}, null);
	}

	private void patchIndexAdd(String path, NodeSnapshot snap)
	{
		if (index.count == 0)
		{
			return;
		}
		index = index.with(Collections.singletonList(path), Collections.singletonList(snap));
		search.setIndex(index);
		notifyIndexChanged(0, null);
	}

	public static String message(Throwable t)
	{
		if (t instanceof org.apache.zookeeper.KeeperException.NodeExistsException)
		{
			return "That node already exists.";
		}
		if (t instanceof org.apache.zookeeper.KeeperException.NoNodeException)
		{
			return "Node no longer exists.";
		}
		if (t instanceof org.apache.zookeeper.KeeperException.NoAuthException)
		{
			return "Not authorized for this operation (check ACL / authentication).";
		}
		if (t instanceof org.apache.zookeeper.KeeperException.NotEmptyException)
		{
			return "Node still has children.";
		}
		if (t instanceof org.apache.zookeeper.KeeperException.NoChildrenForEphemeralsException)
		{
			return "Ephemeral nodes cannot have children.";
		}
		if (t instanceof org.apache.zookeeper.KeeperException.ConnectionLossException)
		{
			return "Connection to ZooKeeper was lost.";
		}
		String m = t.getMessage();
		return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
	}
}