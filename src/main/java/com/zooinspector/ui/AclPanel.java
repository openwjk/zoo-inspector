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
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;

import com.zooinspector.core.Inspector;
import com.zooinspector.core.NodeSnapshot;
import com.zooinspector.core.ZkConnection;
import com.zooinspector.util.Edt;

/** ACL 面板：scheme / id / 权限勾选，可增删并写回（带 aversion 校验） */
public class AclPanel extends JPanel
{
	private static final String[] SCHEMES = { "world", "auth", "digest", "ip", "sasl" };

	private final Inspector inspector;
	private final DefaultTableModel model = new DefaultTableModel(new Object[] { "Scheme", "Id",
			"Read", "Write", "Create", "Delete", "Admin" }, 0)
	{
		@Override
		public Class<?> getColumnClass(int columnIndex)
		{
			return columnIndex >= 2 ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int row, int column)
		{
			return true;
		}
	};
	private final JTable table = new JTable(model);
	private final JLabel hint = new JLabel(" ");
	private final JButton apply = new JButton("Apply", Icons.get("save"));
	private String path;
	private int aversion = -1;

	public AclPanel(Inspector inspector)
	{
		super(new BorderLayout());
		this.inspector = inspector;
		table.setRowHeight(28);
		table.setShowGrid(false);
		table.getTableHeader().setReorderingAllowed(false);
		Ui.alignHeaders(table);
		JComboBox<String> schemes = new JComboBox<String>(SCHEMES);
		table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(schemes));
		table.getColumnModel().getColumn(0).setPreferredWidth(90);
		table.getColumnModel().getColumn(1).setPreferredWidth(240);
		for (int i = 2; i < 7; i++)
		{
			table.getColumnModel().getColumn(i).setPreferredWidth(62);
		}

		JScrollPane scroll = new JScrollPane(table);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		add(scroll, BorderLayout.CENTER);
		add(buildFooter(), BorderLayout.SOUTH);
		setEnabledState(false);
	}

	private JPanel buildFooter()
	{
		JPanel footer = new JPanel(new BorderLayout(8, 0));
		footer.setBorder(BorderFactory.createEmptyBorder(6, 12, 8, 12));
		hint.setFont(Theme.uiFont(11));
		hint.setForeground(Theme.muted());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.setOpaque(false);
		JButton add = new JButton("Add", Icons.get("plus"));
		add.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				model.addRow(new Object[] { "digest", "", true, true, true, true, true });
				int row = model.getRowCount() - 1;
				table.editCellAt(row, 1);
				table.changeSelection(row, 1, false, false);
			}
		});
		JButton remove = new JButton("Remove", Icons.get("trash"));
		remove.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				int row = table.getSelectedRow();
				if (row >= 0)
				{
					model.removeRow(row);
				}
			}
		});
		JButton openAll = new JButton("world:anyone", Icons.get("lock"));
		openAll.setToolTipText("Replace with the fully open ACL (world:anyone, all permissions)");
		openAll.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				model.setRowCount(0);
				model.addRow(new Object[] { "world", "anyone", true, true, true, true, true });
			}
		});
		apply.setToolTipText("Write ACL to the server");
		apply.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				applyNow();
			}
		});
		buttons.add(openAll);
		buttons.add(add);
		buttons.add(remove);
		buttons.add(apply);

		footer.add(hint, BorderLayout.WEST);
		footer.add(buttons, BorderLayout.EAST);
		return footer;
	}

	private void setEnabledState(boolean enabled)
	{
		table.setEnabled(enabled);
		apply.setEnabled(enabled);
	}

	public void clear()
	{
		path = null;
		aversion = -1;
		model.setRowCount(0);
		hint.setText(" ");
		setEnabledState(false);
	}

	public void load(final String nodePath)
	{
		this.path = nodePath;
		Edt.background(new Callable<Object[]>()
		{
			@Override
			public Object[] call() throws Exception
			{
				List<ACL> acls = inspector.connection().acl(nodePath);
				NodeSnapshot snap = inspector.connection().exists(nodePath);
				return new Object[] { acls, snap };
			}
		}, new Edt.Callback<Object[]>()
		{
			@Override
			@SuppressWarnings("unchecked")
			public void accept(Object[] value)
			{
				if (!nodePath.equals(path))
				{
					return;
				}
				List<ACL> acls = (List<ACL>) value[0];
				NodeSnapshot snap = (NodeSnapshot) value[1];
				aversion = snap == null ? -1 : snap.aversion;
				model.setRowCount(0);
				for (ACL acl : acls)
				{
					int p = acl.getPerms();
					model.addRow(new Object[] { acl.getId().getScheme(), acl.getId().getId(),
							(p & ZooDefs.Perms.READ) != 0, (p & ZooDefs.Perms.WRITE) != 0,
							(p & ZooDefs.Perms.CREATE) != 0, (p & ZooDefs.Perms.DELETE) != 0,
							(p & ZooDefs.Perms.ADMIN) != 0 });
				}
				hint.setText("ACL version " + aversion + " · digest ids are written as user:password");
				setEnabledState(true);
			}
		}, new Edt.Callback<Throwable>()
		{
			@Override
			public void accept(Throwable t)
			{
				model.setRowCount(0);
				hint.setText(Inspector.message(t));
				setEnabledState(false);
			}
		});
	}

	private void applyNow()
	{
		if (path == null)
		{
			return;
		}
		if (table.isEditing())
		{
			table.getCellEditor().stopCellEditing();
		}
		final List<ACL> acls = new ArrayList<ACL>();
		for (int row = 0; row < model.getRowCount(); row++)
		{
			String scheme = String.valueOf(model.getValueAt(row, 0));
			String id = String.valueOf(model.getValueAt(row, 1));
			if (id == null || id.trim().isEmpty())
			{
				Toasts.error(this, "Row " + (row + 1) + " has an empty id.");
				return;
			}
			int perms = 0;
			if (Boolean.TRUE.equals(model.getValueAt(row, 2)))
			{
				perms |= ZooDefs.Perms.READ;
			}
			if (Boolean.TRUE.equals(model.getValueAt(row, 3)))
			{
				perms |= ZooDefs.Perms.WRITE;
			}
			if (Boolean.TRUE.equals(model.getValueAt(row, 4)))
			{
				perms |= ZooDefs.Perms.CREATE;
			}
			if (Boolean.TRUE.equals(model.getValueAt(row, 5)))
			{
				perms |= ZooDefs.Perms.DELETE;
			}
			if (Boolean.TRUE.equals(model.getValueAt(row, 6)))
			{
				perms |= ZooDefs.Perms.ADMIN;
			}
			if (perms == 0)
			{
				Toasts.error(this, "Row " + (row + 1) + " has no permissions selected.");
				return;
			}
			acls.add(new ACL(perms, new Id(scheme, id.trim())));
		}
		if (acls.isEmpty())
		{
			Toasts.error(this, "An ACL list must not be empty — you would lock yourself out.");
			return;
		}
		int answer = JOptionPane.showConfirmDialog(this, "Write these ACLs to " + path
				+ "?\nA restrictive ACL can lock you out of this node.", "Confirm ACL change",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (answer != JOptionPane.YES_OPTION)
		{
			return;
		}
		final String target = path;
		inspector.saveAcl(target, acls, aversion, new Inspector.Done()
		{
			@Override
			public void done(String error)
			{
				if (error != null)
				{
					Toasts.error(AclPanel.this, error);
					return;
				}
				Toasts.success(AclPanel.this, "ACL updated for " + target);
				load(target);
			}
		});
	}

	@Override
	public void updateUI()
	{
		super.updateUI();
		if (hint != null)
		{
			hint.setForeground(Theme.muted());
			apply.setIcon(Icons.get("save"));
		}
	}
}