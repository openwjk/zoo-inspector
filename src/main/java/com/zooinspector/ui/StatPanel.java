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
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import com.zooinspector.core.Inspector;
import com.zooinspector.core.NodeSnapshot;
import com.zooinspector.util.Edt;

/** Stat 面板：全部 znode 元数据，时间戳/zxid 同时给可读值与原始值 */
public class StatPanel extends JPanel
{
	private final Inspector inspector;
	private final DefaultTableModel model = new DefaultTableModel(new Object[] { "Field", "Value" }, 0)
	{
		@Override
		public boolean isCellEditable(int row, int column)
		{
			return false;
		}
	};
	private final JTable table = new JTable(model);
	private final JLabel hint = new JLabel(" ");
	private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
	private String path;

	public StatPanel(Inspector inspector)
	{
		super(new BorderLayout());
		this.inspector = inspector;
		table.setRowHeight(26);
		table.setShowGrid(false);
		table.getColumnModel().getColumn(0).setPreferredWidth(170);
		table.getColumnModel().getColumn(1).setPreferredWidth(420);
		table.getColumnModel().getColumn(0).setCellRenderer(new FieldRenderer());
		table.getColumnModel().getColumn(1).setCellRenderer(new ValueRenderer());
		table.getTableHeader().setReorderingAllowed(false);
		Ui.alignHeaders(table);

		JScrollPane scroll = new JScrollPane(table);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		add(scroll, BorderLayout.CENTER);

		JPanel footer = new JPanel(new BorderLayout());
		footer.setBorder(BorderFactory.createEmptyBorder(6, 12, 8, 12));
		hint.setFont(Theme.uiFont(11));
		hint.setForeground(Theme.muted());
		JButton copy = new JButton("Copy all", Icons.get("copy"));
		copy.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < model.getRowCount(); i++)
				{
					sb.append(model.getValueAt(i, 0)).append(": ").append(model.getValueAt(i, 1))
							.append('\n');
				}
				Toolkit.getDefaultToolkit().getSystemClipboard()
						.setContents(new StringSelection(sb.toString()), null);
				Toasts.success(StatPanel.this, "Stat copied to clipboard");
			}
		});
		footer.add(hint, BorderLayout.WEST);
		footer.add(copy, BorderLayout.EAST);
		add(footer, BorderLayout.SOUTH);
	}

	public void clear()
	{
		path = null;
		model.setRowCount(0);
		hint.setText(" ");
	}

	public void load(final String nodePath)
	{
		this.path = nodePath;
		Edt.background(new Callable<NodeSnapshot>()
		{
			@Override
			public NodeSnapshot call() throws Exception
			{
				return inspector.connection().exists(nodePath);
			}
		}, new Edt.Callback<NodeSnapshot>()
		{
			@Override
			public void accept(NodeSnapshot snap)
			{
				if (!nodePath.equals(path))
				{
					return;
				}
				if (snap == null)
				{
					model.setRowCount(0);
					hint.setText("Node no longer exists");
					return;
				}
				fill(snap);
			}
		}, new Edt.Callback<Throwable>()
		{
			@Override
			public void accept(Throwable t)
			{
				model.setRowCount(0);
				hint.setText(Inspector.message(t));
			}
		});
	}

	private void fill(NodeSnapshot s)
	{
		List<Object[]> rows = new ArrayList<Object[]>();
		rows.add(row("path", s.path));
		rows.add(row("type", s.isEphemeral() ? "Ephemeral" : "Persistent"));
		rows.add(row("dataLength", s.dataLength + " bytes"));
		rows.add(row("numChildren", String.valueOf(s.numChildren)));
		rows.add(row("version (data)", String.valueOf(s.version)));
		rows.add(row("cversion (children)", String.valueOf(s.cversion)));
		rows.add(row("aversion (ACL)", String.valueOf(s.aversion)));
		rows.add(row("czxid", zxid(s.czxid)));
		rows.add(row("mzxid", zxid(s.mzxid)));
		rows.add(row("pzxid", zxid(s.pzxid)));
		rows.add(row("ctime", time(s.ctime)));
		rows.add(row("mtime", time(s.mtime)));
		rows.add(row("ephemeralOwner", s.ephemeralOwner == 0 ? "0 (persistent)"
				: "0x" + Long.toHexString(s.ephemeralOwner) + "  (" + s.ephemeralOwner + ")"));
		model.setRowCount(0);
		for (Object[] r : rows)
		{
			model.addRow(r);
		}
		hint.setText("zxid shown as hex and decimal · timestamps in local time");
	}

	private static Object[] row(String field, String value)
	{
		return new Object[] { field, value };
	}

	private static String zxid(long v)
	{
		return "0x" + Long.toHexString(v) + "  (" + v + ")";
	}

	private String time(long epoch)
	{
		if (epoch <= 0)
		{
			return String.valueOf(epoch);
		}
		return format.format(new Date(epoch)) + "  (" + epoch + ")";
	}

	private static final class FieldRenderer extends DefaultTableCellRenderer
	{
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
				boolean focus, int row, int col)
		{
			JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, selected, focus, row,
					col);
			l.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
			l.setForeground(selected ? t.getSelectionForeground() : Theme.muted());
			return l;
		}
	}

	private static final class ValueRenderer extends DefaultTableCellRenderer
	{
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
				boolean focus, int row, int col)
		{
			JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, selected, focus, row,
					col);
			l.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 8));
			l.setFont(Theme.monoFont(t.getFont().getSize()));
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
		}
	}
}