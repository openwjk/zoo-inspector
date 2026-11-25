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
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

import com.zooinspector.core.Inspector;
import com.zooinspector.core.NodeSnapshot;
import com.zooinspector.core.ZkConnection;
import com.zooinspector.util.Edt;
import com.zooinspector.util.ZkPaths;

/**
 * Children 面板：可排序、可过滤的子节点表格。
 * 老版几千个子节点只能靠树逐个展开，这里一屏就能看完并排序定位。
 */
public class ChildrenPanel extends JPanel
{
	public interface Listener
	{
		void navigate(String path);
	}

	private static final int MAX_ROWS = 20000;
	/** ConcurrentHashMap 不允许 null 值，用哨兵表示「节点已消失」 */
	private static final NodeSnapshot GONE = new NodeSnapshot("/\u0000gone", null);

	private final Inspector inspector;
	private final DefaultTableModel model = new DefaultTableModel(new Object[] { "Name", "Type",
			"Data", "Children", "Version", "Modified" }, 0)
	{
		@Override
		public boolean isCellEditable(int row, int column)
		{
			return false;
		}

		@Override
		public Class<?> getColumnClass(int columnIndex)
		{
			switch (columnIndex)
			{
				case 3:
				case 4:
					return Integer.class;
				case 2:
					return Long.class;
				default:
					return String.class;
			}
		}
	};
	private final JTable table = new JTable(model);
	private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<DefaultTableModel>(model);
	private final JTextField filter = new JTextField(16);
	private final JLabel hint = new JLabel(" ");
	private final Map<String, Integer> rowByPath = new HashMap<String, Integer>();
	private final Map<String, NodeSnapshot> pending = new ConcurrentHashMap<String, NodeSnapshot>();
	private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private final Timer flush;

	private String path;
	private Listener listener;
	private long generation;

	public ChildrenPanel(Inspector inspector)
	{
		super(new BorderLayout());
		this.inspector = inspector;
		table.setRowHeight(26);
		table.setShowGrid(false);
		table.setRowSorter(sorter);
		table.getTableHeader().setReorderingAllowed(false);
		Ui.alignHeaders(table, 2, 3, 4);
		table.getColumnModel().getColumn(0).setPreferredWidth(260);
		table.getColumnModel().getColumn(1).setPreferredWidth(90);
		table.getColumnModel().getColumn(2).setPreferredWidth(90);
		table.getColumnModel().getColumn(3).setPreferredWidth(80);
		table.getColumnModel().getColumn(4).setPreferredWidth(70);
		table.getColumnModel().getColumn(5).setPreferredWidth(160);
		table.getColumnModel().getColumn(2).setCellRenderer(new SizeRenderer());
		table.getColumnModel().getColumn(3).setCellRenderer(new PendingNumberRenderer());
		table.getColumnModel().getColumn(4).setCellRenderer(new PendingNumberRenderer());
		table.getColumnModel().getColumn(5).setCellRenderer(new MonoRenderer());
		table.getColumnModel().getColumn(1).setCellRenderer(new TypeRenderer());
		table.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getClickCount() == 2 && listener != null)
				{
					int row = table.getSelectedRow();
					if (row >= 0)
					{
						String name = String.valueOf(model.getValueAt(table.convertRowIndexToModel(row), 0));
						listener.navigate(ZkPaths.join(path, name));
					}
				}
			}
		});

		JScrollPane scroll = new JScrollPane(table);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		add(scroll, BorderLayout.CENTER);
		add(buildHeader(), BorderLayout.NORTH);
		add(buildFooter(), BorderLayout.SOUTH);

		flush = new Timer(90, new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				flushPending();
			}
		});
		flush.setRepeats(true);
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout(8, 0));
		header.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
		filter.putClientProperty("JTextField.placeholderText", "Filter children");
		filter.putClientProperty("JTextField.leadingIcon", Icons.get("search"));
		filter.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				applyFilter();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				applyFilter();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				applyFilter();
			}
		});
		header.add(filter, BorderLayout.WEST);
		return header;
	}

	private JPanel buildFooter()
	{
		JPanel footer = new JPanel(new BorderLayout());
		footer.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
		hint.setFont(Theme.uiFont(11));
		hint.setForeground(Theme.muted());
		footer.add(hint, BorderLayout.WEST);
		return footer;
	}

	public void setListener(Listener l)
	{
		this.listener = l;
	}

	private void applyFilter()
	{
		String text = filter.getText();
		if (text == null || text.isEmpty())
		{
			sorter.setRowFilter(null);
		}
		else
		{
			sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text), 0));
		}
		updateHint();
	}

	public void clear()
	{
		generation++;
		path = null;
		model.setRowCount(0);
		rowByPath.clear();
		pending.clear();
		flush.stop();
		hint.setText(" ");
	}

	public void load(final String nodePath)
	{
		this.path = nodePath;
		final long gen = ++generation;
		model.setRowCount(0);
		rowByPath.clear();
		pending.clear();
		flush.stop();
		hint.setText("Loading…");
		Edt.background(new Callable<ZkConnection.Children>()
		{
			@Override
			public ZkConnection.Children call() throws Exception
			{
				return inspector.connection().children(nodePath);
			}
		}, new Edt.Callback<ZkConnection.Children>()
		{
			@Override
			public void accept(ZkConnection.Children children)
			{
				if (gen != generation || !nodePath.equals(path))
				{
					return;
				}
				List<String> names = children.names;
				boolean truncated = names.size() > MAX_ROWS;
				List<String> shown = truncated ? names.subList(0, MAX_ROWS) : names;
				List<Object[]> rows = new ArrayList<Object[]>(shown.size());
				for (String name : shown)
				{
					String childPath = ZkPaths.join(nodePath, name);
					rowByPath.put(childPath, Integer.valueOf(rows.size()));
					rows.add(new Object[] { name, "…", Long.valueOf(-1), Integer.valueOf(-1),
							Integer.valueOf(-1), "" });
				}
				model.setRowCount(0);
				for (Object[] r : rows)
				{
					model.addRow(r);
				}
				updateHint();
				if (truncated)
				{
					hint.setText("showing first " + MAX_ROWS + " of " + names.size() + " children");
				}
				prefetch(nodePath, shown, gen);
			}
		}, new Edt.Callback<Throwable>()
		{
			@Override
			public void accept(Throwable t)
			{
				if (gen == generation)
				{
					hint.setText(Inspector.message(t));
				}
			}
		});
	}

	/** 异步 exists 流水线补齐每个子节点的 stat，批量刷表 */
	private void prefetch(String parent, List<String> names, final long gen)
	{
		flush.start();
		for (String name : names)
		{
			String childPath = ZkPaths.join(parent, name);
			inspector.connection().existsAsync(childPath, new ZkConnection.SnapshotCallback()
			{
				@Override
				public void done(String p, NodeSnapshot snapshot, Throwable error)
				{
					if (gen != generation)
					{
						return;
					}
					if (snapshot != null)
					{
						pending.put(p, snapshot);
					}
					else
					{
						pending.put(p, GONE);
					}
				}
			});
		}
	}

	private void flushPending()
	{
		if (pending.isEmpty())
		{
			if (rowByPath.isEmpty())
			{
				flush.stop();
			}
			return;
		}
		Map<String, NodeSnapshot> batch = new HashMap<String, NodeSnapshot>(pending);
		pending.clear();
		for (Map.Entry<String, NodeSnapshot> e : batch.entrySet())
		{
			Integer row = rowByPath.get(e.getKey());
			if (row == null || row.intValue() >= model.getRowCount())
			{
				continue;
			}
			NodeSnapshot s = e.getValue();
			int r = row.intValue();
			if (s == GONE)
			{
				model.setValueAt("gone", r, 1);
				continue;
			}
			model.setValueAt(s.isEphemeral() ? "Ephemeral" : "Persistent", r, 1);
			model.setValueAt(Long.valueOf(s.dataLength), r, 2);
			model.setValueAt(Integer.valueOf(s.numChildren), r, 3);
			model.setValueAt(Integer.valueOf(s.version), r, 4);
			model.setValueAt(s.mtime > 0 ? format.format(new Date(s.mtime)) : "", r, 5);
		}
		updateHint();
	}

	private void updateHint()
	{
		if (path == null)
		{
			return;
		}
		int total = model.getRowCount();
		int visible = table.getRowCount();
		String text = visible == total ? total + " children" : visible + " of " + total + " children";
		if (!pending.isEmpty())
		{
			text += " · loading stats…";
		}
		hint.setText(text);
	}

	private static final class SizeRenderer extends DefaultTableCellRenderer
	{
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
				boolean focus, int row, int col)
		{
			Object display = value;
			if (value instanceof Number)
			{
				long v = ((Number) value).longValue();
				display = v < 0 ? "…" : TreePanel.formatSize((int) v);
			}
			JLabel l = (JLabel) super.getTableCellRendererComponent(t, display, selected, focus, row,
					col);
			l.setHorizontalAlignment(RIGHT);
			l.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 10));
			return l;
		}
	}

	/** stat 还没补齐时显示省略号，而不是 -1 */
	private static final class PendingNumberRenderer extends DefaultTableCellRenderer
	{
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
				boolean focus, int row, int col)
		{
			Object display = value;
			if (value instanceof Number && ((Number) value).intValue() < 0)
			{
				display = "…";
			}
			JLabel l = (JLabel) super.getTableCellRendererComponent(t, display, selected, focus, row,
					col);
			l.setHorizontalAlignment(RIGHT);
			l.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 10));
			if (!selected && "…".equals(display))
			{
				l.setForeground(Theme.muted());
			}
			return l;
		}
	}

	private static final class MonoRenderer extends DefaultTableCellRenderer
	{
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
				boolean focus, int row, int col)
		{
			JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, selected, focus, row,
					col);
			l.setFont(Theme.monoFont(t.getFont().getSize() - 1));
			l.setForeground(selected ? t.getSelectionForeground() : Theme.muted());
			return l;
		}
	}

	private static final class TypeRenderer extends DefaultTableCellRenderer
	{
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
				boolean focus, int row, int col)
		{
			JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, selected, focus, row,
					col);
			if ("Ephemeral".equals(value))
			{
				l.setForeground(selected ? t.getSelectionForeground() : Theme.warn());
			}
			else if (!selected)
			{
				l.setForeground(Theme.muted());
			}
			return l;
		}
	}

	@Override
	public void updateUI()
	{
		super.updateUI();
		if (hint != null)
		{
			hint.setForeground(Theme.muted());
			filter.putClientProperty("JTextField.leadingIcon", Icons.get("search"));
		}
	}
}