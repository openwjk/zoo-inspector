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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;

import com.zooinspector.core.ConnectProfile;
import com.zooinspector.core.Inspector;
import com.zooinspector.core.PathIndex;
import com.zooinspector.core.ZkConnection;
import com.zooinspector.util.Edt;
import com.zooinspector.util.ZkPaths;

/** 主窗口：顶部连接与搜索、左侧树/搜索结果、右侧详情、底部状态栏 */
public class MainFrame extends JFrame implements TreePanel.Actions, SearchBar.Listener,
		SearchResultsPanel.Listener, TreePanel.NodeSelectionListener, NodeDetailPanel.Listener,
		Inspector.IndexListener
{
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final String CARD_TREE = "tree";
	private static final String CARD_RESULTS = "results";

	private final Inspector inspector;
	private final SearchBar searchBar = new SearchBar();
	private final SearchResultsPanel resultsPanel = new SearchResultsPanel();
	private final TreePanel treePanel;
	private final NodeDetailPanel detailPanel;
	private final StatusBar statusBar = new StatusBar();

	private final JComboBox<ConnectProfile> profileBox = new JComboBox<ConnectProfile>();
	private final JButton connectButton = new JButton("Connect", Icons.get("connect"));
	private final JButton editConnectionButton = new JButton(Icons.get("plus"));
	private final JButton disconnectButton = new JButton(Icons.get("disconnect"));
	private final JButton refreshButton = new JButton(Icons.get("refresh"));
	private final JButton indexButton = new JButton(Icons.get("index"));
	private final JButton themeButton = new JButton();
	private final JButton settingsButton = new JButton(Icons.get("settings"));
	private final JButton helpButton = new JButton(Icons.get("info"));

	private final CardLayout leftCards = new CardLayout();
	private final JPanel leftCardPanel = new JPanel();
	private final JLabel leftTitle = new JLabel("Nodes");
	private final JButton backToTree = new JButton("Tree", Icons.get("folder"));
	private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

	private ConnectProfile currentProfile;
	private boolean connecting;

	public MainFrame(Inspector inspector)
	{
		super("ZooInspector");
		this.inspector = inspector;
		this.treePanel = new TreePanel(inspector);
		this.detailPanel = new NodeDetailPanel(inspector);

		setIconImage(Icons.appImage(64));
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		setSize(1220, 780);
		setMinimumSize(new Dimension(900, 560));
		setLocationRelativeTo(null);

		treePanel.setActions(this);
		treePanel.setSelectionListener(this);
		searchBar.setListener(this);
		resultsPanel.setListener(this);
		detailPanel.setListener(this);
		inspector.addIndexListener(this);
		inspector.search().setListener(new com.zooinspector.core.SearchEngine.Listener()
		{
			@Override
			public void results(PathIndex.Result result, String query, PathIndex.Mode mode)
			{
				onResults(result, query, mode);
			}
		});
		inspector.connection().addStateListener(new ZkConnection.StateListener()
		{
			@Override
			public void stateChanged(final ZkConnection.State state, final String detail)
			{
				Edt.run(new Runnable()
				{
					@Override
					public void run()
					{
						onConnectionState(state, detail);
					}
				});
			}
		});

		JPanel root = new JPanel(new BorderLayout());
		root.add(buildTop(), BorderLayout.NORTH);
		root.add(buildCenter(), BorderLayout.CENTER);
		root.add(statusBar, BorderLayout.SOUTH);
		setContentPane(root);

		installShortcuts();
		refreshProfiles();
		searchBar.setGrouped(inspector.settings().groupInterfaces);
		updateConnectionUi();
		updateThemeButton();
		statusBar.setConnection(ZkConnection.State.DISCONNECTED, null, 0);
		statusBar.setIndex(PathIndex.EMPTY, null);

		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				shutdown();
			}
		});
	}

	// ---------------- 布局 ----------------

	private JPanel buildTop()
	{
		JPanel top = new JPanel(new BorderLayout());
		JPanel header = new JPanel(new BorderLayout());
		header.setBorder(BorderFactory.createEmptyBorder(8, 12, 2, 12));

		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		left.setOpaque(false);
		JLabel title = new JLabel("ZooInspector");
		title.setFont(Theme.uiFont(Font.BOLD, 15));
		title.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
		left.add(title);
		profileBox.setPreferredSize(new Dimension(190, 30));
		profileBox.setToolTipText("Saved connections");
		profileBox.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				Object sel = profileBox.getSelectedItem();
				if (sel instanceof ConnectProfile)
				{
					currentProfile = (ConnectProfile) sel;
				}
			}
		});
		left.add(profileBox);
		editConnectionButton.setToolTipText("New or edited connection…");
		editConnectionButton.setPreferredSize(new Dimension(32, 30));
		editConnectionButton.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				openConnectDialog();
			}
		});
		left.add(editConnectionButton);
		connectButton.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				connectSelected();
			}
		});
		left.add(connectButton);
		disconnectButton.setToolTipText("Disconnect");
		disconnectButton.setPreferredSize(new Dimension(32, 30));
		disconnectButton.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				doDisconnect();
			}
		});
		left.add(disconnectButton);
		refreshButton.setToolTipText("Refresh tree and index (Ctrl+R)");
		refreshButton.setPreferredSize(new Dimension(32, 30));
		refreshButton.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				refreshAll();
			}
		});
		left.add(refreshButton);
		indexButton.setToolTipText("Rebuild search index (Ctrl+I)");
		indexButton.setPreferredSize(new Dimension(32, 30));
		indexButton.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				rebuildIndex();
			}
		});
		left.add(indexButton);

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		right.setOpaque(false);
		themeButton.setPreferredSize(new Dimension(32, 30));
		themeButton.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				toggleTheme();
			}
		});
		settingsButton.setToolTipText("Settings");
		settingsButton.setPreferredSize(new Dimension(32, 30));
		settingsButton.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				openSettings();
			}
		});
		helpButton.setToolTipText("Keyboard shortcuts");
		helpButton.setPreferredSize(new Dimension(32, 30));
		helpButton.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				showHelp();
			}
		});
		for (JButton b : new JButton[] { themeButton, settingsButton, helpButton })
		{
			b.putClientProperty("JButton.buttonType", "borderless");
			right.add(b);
		}

		header.add(left, BorderLayout.WEST);
		header.add(right, BorderLayout.EAST);
		top.add(header, BorderLayout.NORTH);
		top.add(searchBar, BorderLayout.SOUTH);
		top.add(new javax.swing.JSeparator(), BorderLayout.CENTER);
		return top;
	}

	private JComponent buildCenter()
	{
		leftCardPanel.setLayout(leftCards);
		leftCardPanel.add(treePanel, CARD_TREE);
		leftCardPanel.add(resultsPanel, CARD_RESULTS);

		JPanel leftPanel = new JPanel(new BorderLayout());
		JPanel leftHeader = new JPanel(new BorderLayout());
		leftHeader.setBorder(BorderFactory.createEmptyBorder(6, 12, 2, 8));
		leftTitle.setFont(Theme.uiFont(Font.BOLD, 11));
		leftTitle.setForeground(Theme.muted());
		backToTree.putClientProperty("JButton.buttonType", "borderless");
		backToTree.setFont(Theme.uiFont(11));
		backToTree.setVisible(false);
		backToTree.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				showTreeCard();
			}
		});
		leftHeader.add(leftTitle, BorderLayout.WEST);
		leftHeader.add(backToTree, BorderLayout.EAST);
		leftPanel.add(leftHeader, BorderLayout.NORTH);
		leftPanel.add(leftCardPanel, BorderLayout.CENTER);
		leftPanel.setMinimumSize(new Dimension(240, 200));

		split.setLeftComponent(leftPanel);
		split.setRightComponent(detailPanel);
		split.setDividerLocation(inspector.settings().dividerLocation);
		split.setResizeWeight(0.28);
		split.setBorder(BorderFactory.createEmptyBorder());
		split.setContinuousLayout(true);
		return split;
	}

	private void installShortcuts()
	{
		bind("ctrl F", "focusSearch", new Runnable()
		{
			@Override
			public void run()
			{
				searchBar.focusField();
			}
		});
		bind("ctrl R", "refreshAll", new Runnable()
		{
			@Override
			public void run()
			{
				refreshAll();
			}
		});
		bind("ctrl I", "rebuildIndex", new Runnable()
		{
			@Override
			public void run()
			{
				rebuildIndex();
			}
		});
		bind("ctrl S", "saveData", new Runnable()
		{
			@Override
			public void run()
			{
				detailPanel.saveData();
			}
		});
		bind("ctrl N", "newNode", new Runnable()
		{
			@Override
			public void run()
			{
				String parent = treePanel.selectedPath();
				if (parent == null)
				{
					parent = detailPanel.currentPath();
				}
				if (parent != null)
				{
					createChild(parent);
				}
			}
		});
		bind("DELETE", "deleteNode", new Runnable()
		{
			@Override
			public void run()
			{
				String path = treePanel.selectedPath();
				if (path != null)
				{
					deleteNode(path);
				}
			}
		});
		bind("ctrl shift C", "copyPath", new Runnable()
		{
			@Override
			public void run()
			{
				detailPanel.copyPath();
			}
		});
		bind("ctrl shift F", "findInData", new Runnable()
		{
			@Override
			public void run()
			{
				detailPanel.focusDataFind();
			}
		});
		bind("ctrl shift D", "toggleTheme", new Runnable()
		{
			@Override
			public void run()
			{
				toggleTheme();
			}
		});
	}

	private void bind(String keyStroke, String name, final Runnable action)
	{
		JComponent root = getRootPane();
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyStroke), name);
		root.getActionMap().put(name, new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				action.run();
			}
		});
	}

	// ---------------- 连接 ----------------

	private void refreshProfiles()
	{
		List<ConnectProfile> profiles = inspector.profiles();
		DefaultComboBoxModel<ConnectProfile> model = new DefaultComboBoxModel<ConnectProfile>();
		for (ConnectProfile p : profiles)
		{
			model.addElement(p);
		}
		profileBox.setModel(model);
		String last = inspector.settings().lastProfile;
		for (int i = 0; i < model.getSize(); i++)
		{
			if (model.getElementAt(i).displayName().equals(last))
			{
				profileBox.setSelectedIndex(i);
				break;
			}
		}
		Object sel = profileBox.getSelectedItem();
		currentProfile = sel instanceof ConnectProfile ? (ConnectProfile) sel : new ConnectProfile();
	}

	private void connectSelected()
	{
		if (currentProfile == null || currentProfile.hosts == null
				|| currentProfile.hosts.trim().isEmpty())
		{
			openConnectDialog();
			return;
		}
		doConnect(currentProfile);
	}

	/** 命令行 --connect 用：临时 profile，不写入磁盘 */
	public void connectHosts(String hosts)
	{
		ConnectProfile p = new ConnectProfile();
		p.hosts = hosts;
		p.name = hosts;
		currentProfile = p;
		doConnect(p);
	}

	/** 以编程方式发起一次搜索（自动化验证用） */
	public void applySearch(String text, PathIndex.Mode mode)
	{
		searchBar.setMode(mode);
		searchBar.setText(text);
	}

	public void applySearch(String text, PathIndex.Mode mode, boolean grouped)
	{
		searchBar.setGrouped(grouped);
		searchBar.setMode(mode);
		searchBar.setText(text);
	}

	public SearchResultsPanel resultsPanel()
	{
		return resultsPanel;
	}

	public Inspector inspector()
	{
		return inspector;
	}

	public TreePanel treePanel()
	{
		return treePanel;
	}

	public NodeDetailPanel detailPanel()
	{
		return detailPanel;
	}

	private void openConnectDialog()
	{
		ConnectDialog dialog = new ConnectDialog(this, currentProfile);
		dialog.setVisible(true);
		ConnectProfile result = dialog.result();
		if (result == null)
		{
			return;
		}
		List<ConnectProfile> profiles = new ArrayList<ConnectProfile>(inspector.profiles());
		boolean replaced = false;
		for (int i = 0; i < profiles.size(); i++)
		{
			if (profiles.get(i).displayName().equals(result.displayName()))
			{
				profiles.set(i, result);
				replaced = true;
				break;
			}
		}
		if (!replaced)
		{
			profiles.add(result);
		}
		inspector.setProfiles(profiles);
		inspector.settings().lastProfile = result.displayName();
		inspector.saveSettings();
		refreshProfiles();
		for (int i = 0; i < profileBox.getItemCount(); i++)
		{
			if (profileBox.getItemAt(i).displayName().equals(result.displayName()))
			{
				profileBox.setSelectedIndex(i);
				break;
			}
		}
		currentProfile = result;
		doConnect(result);
	}

	private void doConnect(final ConnectProfile profile)
	{
		if (connecting)
		{
			return;
		}
		if (!detailPanel.confirmDiscard())
		{
			return;
		}
		connecting = true;
		connectButton.setEnabled(false);
		statusBar.setConnection(ZkConnection.State.CONNECTING, profile.hosts, 0);
		treePanel.clear();
		detailPanel.clear();
		inspector.connect(profile, new Inspector.Done()
		{
			@Override
			public void done(String error)
			{
				connecting = false;
				updateConnectionUi();
				if (error != null)
				{
					Toasts.error(MainFrame.this, error);
					return;
				}
				Toasts.success(MainFrame.this, "Connected to " + profile.hosts);
				treePanel.reveal(ZkPaths.ROOT);
			}
		});
	}

	private void doDisconnect()
	{
		if (!detailPanel.confirmDiscard())
		{
			return;
		}
		inspector.disconnect(new Inspector.Done()
		{
			@Override
			public void done(String error)
			{
				treePanel.clear();
				detailPanel.clear();
				searchBar.clearSearch();
				showTreeCard();
				updateConnectionUi();
			}
		});
	}

	private void onConnectionState(ZkConnection.State state, String detail)
	{
		ConnectProfile p = inspector.connection().profile();
		statusBar.setConnection(state, p == null ? null : p.hosts,
				inspector.connection().sessionId());
		updateConnectionUi();
		if (state == ZkConnection.State.LOST)
		{
			Toasts.warn(this, "Connection lost — the client is retrying.");
		}
		else if (state == ZkConnection.State.EXPIRED)
		{
			Toasts.error(this, "Session expired" + (detail == null ? "" : " (" + detail + ")")
					+ ". Reconnect to continue.");
		}
	}

	private void updateConnectionUi()
	{
		boolean usable = inspector.connection().isUsable();
		connectButton.setEnabled(!usable && !connecting);
		disconnectButton.setEnabled(usable);
		refreshButton.setEnabled(usable);
		indexButton.setEnabled(usable);
		profileBox.setEnabled(!usable);
		editConnectionButton.setEnabled(!usable);
	}

	// ---------------- 搜索 ----------------

	@Override
	public void queryChanged(String text, PathIndex.Mode mode)
	{
		boolean grouped = searchBar.isGrouped();
		inspector.search().setGroupInterfaces(grouped);
		if (inspector.settings().groupInterfaces != grouped)
		{
			inspector.settings().groupInterfaces = grouped;
			inspector.saveSettings();
		}
		if (text == null || text.isEmpty())
		{
			inspector.search().query("", mode);
			showTreeCard();
			return;
		}
		if (mode == PathIndex.Mode.DATA && !inspector.hasDataIndex() && !inspector.isIndexing())
		{
			if (!inspector.connection().isUsable())
			{
				Toasts.warn(this, "Connect first, then Data search can index node values.");
				return;
			}
			Toasts.info(this, "Indexing node values for Data search…");
			inspector.rebuildIndex(true);
		}
		showResultsCard();
		inspector.search().query(text, mode);
	}

	private void onResults(PathIndex.Result result, String query, PathIndex.Mode mode)
	{
		boolean grouped = inspector.search().isGroupInterfaces();
		resultsPanel.showResult(result, query, mode, inspector.index(), grouped);
		if (result.error != null)
		{
			searchBar.setStats(result.error);
			return;
		}
		if (query.isEmpty())
		{
			searchBar.setStats("");
			return;
		}
		StringBuilder sb = new StringBuilder();
		if (grouped && result.rawTotal > result.total)
		{
			sb.append(String.format("%,d", result.total))
					.append(result.total == 1 ? " interface · " : " interfaces · ")
					.append(String.format("%,d", result.rawTotal)).append(" nodes");
		}
		else
		{
			sb.append(String.format("%,d", result.total)).append(result.total == 1 ? " hit" : " hits");
		}
		if (result.hits.size() < result.total)
		{
			sb.append(" · showing ").append(result.hits.size());
		}
		sb.append(" · ").append(result.elapsedMs).append(" ms");
		if (result.partial)
		{
			sb.append(" …");
		}
		searchBar.setStats(sb.toString());
		leftTitle.setText("Search results");
	}

	private void showResultsCard()
	{
		leftCards.show(leftCardPanel, CARD_RESULTS);
		leftTitle.setText("Search results");
		backToTree.setVisible(true);
	}

	private void showTreeCard()
	{
		leftCards.show(leftCardPanel, CARD_TREE);
		leftTitle.setText("Nodes");
		backToTree.setVisible(false);
	}

	@Override
	public void enterPressed()
	{
		// 聚合视图里回车先「点开」接口，而不是跳走
		if (inspector.search().isGroupInterfaces() && resultsPanel.expandSelected())
		{
			return;
		}
		String path = resultsPanel.selectedPath();
		if (path == null)
		{
			resultsPanel.selectFirst();
			path = resultsPanel.selectedPath();
		}
		if (path != null)
		{
			hitActivated(path);
		}
	}

	@Override
	public void escapePressed()
	{
		searchBar.clearSearch();
		showTreeCard();
		treePanel.tree().requestFocusInWindow();
	}

	@Override
	public void downPressed()
	{
		resultsPanel.focusList();
	}

	@Override
	public void hitSelected(String path)
	{
		selectNode(path);
	}

	@Override
	public void hitActivated(String path)
	{
		selectNode(path);
		showTreeCard();
		treePanel.reveal(path);
	}

	@Override
	public void nodeSelected(String path)
	{
		selectNode(path);
	}

	private void selectNode(String path)
	{
		if (path == null || path.equals(detailPanel.currentPath()))
		{
			return;
		}
		if (!detailPanel.confirmDiscard())
		{
			return;
		}
		detailPanel.show(path);
	}

	@Override
	public void revealInTree(String path)
	{
		showTreeCard();
		treePanel.reveal(path);
	}

	@Override
	public void navigate(String path)
	{
		selectNode(path);
		showTreeCard();
		treePanel.reveal(path);
	}

	// ---------------- 索引与刷新 ----------------

	private void rebuildIndex()
	{
		if (!inspector.connection().isUsable())
		{
			Toasts.warn(this, "Connect to a server first.");
			return;
		}
		inspector.rebuildIndex(searchBar.mode() == PathIndex.Mode.DATA);
	}

	private void refreshAll()
	{
		if (!inspector.connection().isUsable())
		{
			return;
		}
		String selected = treePanel.selectedPath();
		treePanel.refreshSubtree(selected);
		detailPanel.reloadCurrent();
		inspector.rebuildIndex(inspector.hasDataIndex());
	}

	@Override
	public void indexProgress(int indexed, int pending, String phase)
	{
		statusBar.setProgress(indexed, pending, phase);
	}

	@Override
	public void indexChanged(PathIndex index, long elapsedMs, String note)
	{
		statusBar.hideProgress();
		String hint = note;
		if (hint == null && elapsedMs > 0)
		{
			hint = "built in " + elapsedMs + " ms";
		}
		if (inspector.isIndexStale())
		{
			hint = (hint == null ? "" : hint + " · ") + "index may be stale — Ctrl+I to rebuild";
		}
		statusBar.setIndex(index, hint);
	}

	// ---------------- 节点操作 ----------------

	@Override
	public void createChild(String parentPath)
	{
		if (!inspector.connection().isUsable())
		{
			Toasts.warn(this, "Connect to a server first.");
			return;
		}
		NewNodeDialog dialog = new NewNodeDialog(this, parentPath);
		dialog.setVisible(true);
		if (!dialog.accepted())
		{
			return;
		}
		final String name = dialog.nodeName();
		inspector.createNode(parentPath, name, dialog.nodeData(), dialog.createMode(), null,
				new Inspector.Done()
				{
					@Override
					public void done(String error)
					{
						if (error != null)
						{
							Toasts.error(MainFrame.this, error);
							return;
						}
						String created = ZkPaths.join(parentPath, name);
						Toasts.success(MainFrame.this, "Created " + created);
						treePanel.reveal(created);
						detailPanel.reloadCurrent();
					}
				});
	}

	@Override
	public void deleteNode(final String path)
	{
		if (!inspector.connection().isUsable())
		{
			return;
		}
		if (ZkPaths.ROOT.equals(path))
		{
			Toasts.warn(this, "The root node cannot be deleted.");
			return;
		}
		com.zooinspector.core.ZkTreeModel.Node node = inspector.tree().nodeFor(path);
		int children = node != null && node.snapshot != null ? node.snapshot.numChildren : -1;
		String message = "Delete " + path + "?";
		if (children > 0)
		{
			message = "Delete " + path + " and its whole subtree?\nIt has " + children
					+ " direct children.";
		}
		int answer = JOptionPane.showConfirmDialog(this,
				message + "\nThis cannot be undone.", "Confirm delete", JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE);
		if (answer != JOptionPane.YES_OPTION)
		{
			return;
		}
		inspector.deleteRecursive(path, new Inspector.Done()
		{
			@Override
			public void done(String error)
			{
				if (error != null)
				{
					Toasts.error(MainFrame.this, error);
					return;
				}
				Toasts.success(MainFrame.this, "Deleted " + path);
				if (path.equals(detailPanel.currentPath()))
				{
					detailPanel.clear();
				}
			}
		});
	}

	@Override
	public void editData(String path)
	{
		selectNode(path);
		detailPanel.selectDataTab();
	}

	@Override
	public void copyPath(String path)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(path), null);
		Toasts.success(this, "Path copied");
	}

	@Override
	public void copyData(final String path)
	{
		Edt.background(new Callable<String>()
		{
			@Override
			public String call() throws Exception
			{
				return new String(inspector.connection().data(path).bytes, UTF8);
			}
		}, new Edt.Callback<String>()
		{
			@Override
			public void accept(String text)
			{
				Toolkit.getDefaultToolkit().getSystemClipboard()
						.setContents(new StringSelection(text), null);
				Toasts.success(MainFrame.this, "Data copied");
			}
		}, new Edt.Callback<Throwable>()
		{
			@Override
			public void accept(Throwable t)
			{
				Toasts.error(MainFrame.this, Inspector.message(t));
			}
		});
	}

	// ---------------- 主题与设置 ----------------

	private void toggleTheme()
	{
		inspector.settings().theme = Theme.isDark() ? "light" : "dark";
		inspector.saveSettings();
		Theme.install(inspector.settings().theme, inspector.settings().uiFontSize);
		updateThemeButton();
		statusBar.setConnection(inspector.connection().state(),
				inspector.connection().profile() == null ? null
						: inspector.connection().profile().hosts,
				inspector.connection().sessionId());
		statusBar.setIndex(inspector.index(), null);
		leftTitle.setForeground(Theme.muted());
		repaint();
	}

	private void updateThemeButton()
	{
		themeButton.setIcon(Icons.get(Theme.isDark() ? "sun" : "moon"));
		themeButton.setToolTipText(Theme.isDark() ? "Switch to light theme (Ctrl+Shift+D)"
				: "Switch to dark theme (Ctrl+Shift+D)");
	}

	private void openSettings()
	{
		SettingsDialog dialog = new SettingsDialog(this, inspector.settings());
		dialog.setVisible(true);
		if (!dialog.accepted())
		{
			return;
		}
		inspector.saveSettings();
		Theme.install(inspector.settings().theme, inspector.settings().uiFontSize);
		updateThemeButton();
		statusBar.setIndex(inspector.index(), null);
		leftTitle.setForeground(Theme.muted());
		Toasts.info(this, "Settings applied.");
	}

	private void showHelp()
	{
		JOptionPane.showMessageDialog(this, "<html><table cellpadding='3'>"
				+ "<tr><td><b>Ctrl+F</b></td><td>Focus search</td></tr>"
				+ "<tr><td><b>Esc</b></td><td>Clear search, back to tree</td></tr>"
				+ "<tr><td><b>Enter</b></td><td>Reveal selected hit in the tree</td></tr>"
				+ "<tr><td><b>Ctrl+I</b></td><td>Rebuild search index</td></tr>"
				+ "<tr><td><b>Ctrl+R</b></td><td>Refresh subtree, node and index</td></tr>"
				+ "<tr><td><b>Ctrl+N</b></td><td>New child node</td></tr>"
				+ "<tr><td><b>Delete</b></td><td>Delete selected node</td></tr>"
				+ "<tr><td><b>Ctrl+S</b></td><td>Save node data</td></tr>"
				+ "<tr><td><b>Ctrl+Shift+F</b></td><td>Find inside node data</td></tr>"
				+ "<tr><td><b>Ctrl+Shift+C</b></td><td>Copy node path</td></tr>"
				+ "<tr><td><b>Ctrl+Shift+D</b></td><td>Toggle light / dark theme</td></tr>"
				+ "</table></html>", "Keyboard shortcuts", JOptionPane.INFORMATION_MESSAGE);
	}

	private void shutdown()
	{
		if (!detailPanel.confirmDiscard())
		{
			return;
		}
		inspector.settings().dividerLocation = split.getDividerLocation();
		inspector.saveSettings();
		inspector.cancelIndex();
		inspector.search().shutdown();
		Edt.submit(new Runnable()
		{
			@Override
			public void run()
			{
				inspector.connection().close();
			}
		});
		dispose();
		System.exit(0);
	}
}