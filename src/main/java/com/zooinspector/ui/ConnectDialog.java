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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import com.zooinspector.core.ConnectProfile;
import com.zooinspector.core.Inspector;
import com.zooinspector.core.ZkConnection;
import com.zooinspector.util.Edt;

/** 连接对话框：地址/超时/digest 认证，可先测试再连接，配置存为 profile */
public class ConnectDialog extends JDialog
{
	private final JTextField name = new JTextField(20);
	private final JTextField hosts = new JTextField(28);
	private final JSpinner sessionTimeout = new JSpinner(new SpinnerNumberModel(30000, 1000, 600000, 1000));
	private final JSpinner connectTimeout = new JSpinner(new SpinnerNumberModel(15000, 1000, 120000, 1000));
	private final JCheckBox remember = new JCheckBox("Remember passwords on disk (plain text)");
	private final DefaultTableModel authModel = new DefaultTableModel(new Object[] { "Scheme", "Id",
			"Secret" }, 0);
	private final JTable authTable = new JTable(authModel);
	private final JLabel status = new JLabel(" ");
	private ConnectProfile result;

	public ConnectDialog(Window owner, ConnectProfile initial)
	{
		super(owner, "Connect to ZooKeeper", ModalityType.APPLICATION_MODAL);
		ConnectProfile p = initial == null ? new ConnectProfile() : initial.copy();
		name.setText(p.name);
		hosts.setText(p.hosts);
		sessionTimeout.setValue(Integer.valueOf(p.sessionTimeoutMs));
		connectTimeout.setValue(Integer.valueOf(p.connectTimeoutMs));
		remember.setSelected(p.rememberSecrets);
		for (ConnectProfile.Auth a : p.auths)
		{
			authModel.addRow(new Object[] { a.scheme, a.id, a.secret });
		}

		JPanel form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(16, 18, 8, 18));
		int row = 0;
		addRow(form, row++, "Profile name", name, "Optional label shown in the toolbar");
		addRow(form, row++, "Connect string", hosts,
				"host1:2181,host2:2181 — append /chroot to scope the session");
		addRow(form, row++, "Session timeout", sessionTimeout, "milliseconds");
		addRow(form, row++, "Connect timeout", connectTimeout, "milliseconds to wait for the session");

		JPanel authPanel = new JPanel(new BorderLayout(0, 6));
		authPanel.setOpaque(false);
		authTable.setRowHeight(26);
		authTable.getColumnModel().getColumn(0).setCellEditor(
				new DefaultCellEditor(new JComboBox<String>(new String[] { "digest", "ip", "sasl", "auth" })));
		authTable.getColumnModel().getColumn(0).setPreferredWidth(80);
		authTable.getColumnModel().getColumn(1).setPreferredWidth(150);
		authTable.getColumnModel().getColumn(2).setPreferredWidth(150);
		authTable.getColumnModel().getColumn(2).setCellRenderer(new SecretRenderer());
		JScrollPane authScroll = new JScrollPane(authTable);
		authScroll.setPreferredSize(new Dimension(420, 92));
		JPanel authButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		authButtons.setOpaque(false);
		JButton addAuth = new JButton("Add", Icons.get("plus"));
		addAuth.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				authModel.addRow(new Object[] { "digest", "", "" });
			}
		});
		JButton removeAuth = new JButton("Remove", Icons.get("trash"));
		removeAuth.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				int r = authTable.getSelectedRow();
				if (r >= 0)
				{
					authModel.removeRow(r);
				}
			}
		});
		authButtons.add(addAuth);
		authButtons.add(removeAuth);
		authPanel.add(authScroll, BorderLayout.CENTER);
		authPanel.add(authButtons, BorderLayout.SOUTH);
		addRow(form, row++, "Authentication", authPanel,
				"digest id is the user name, secret is its password");

		GridBagConstraints gc = new GridBagConstraints();
		gc.gridx = 1;
		gc.gridy = row++;
		gc.anchor = GridBagConstraints.WEST;
		gc.insets = new Insets(2, 0, 2, 0);
		remember.setFont(Theme.uiFont(11));
		form.add(remember, gc);

		JPanel bottom = new JPanel(new BorderLayout(8, 0));
		bottom.setBorder(BorderFactory.createEmptyBorder(6, 18, 14, 18));
		status.setFont(Theme.uiFont(11));
		status.setForeground(Theme.muted());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		JButton test = new JButton("Test", Icons.get("connect"));
		test.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				testConnection(test);
			}
		});
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				result = null;
				dispose();
			}
		});
		JButton connect = new JButton("Connect", Icons.get("check"));
		connect.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				if (hosts.getText().trim().isEmpty())
				{
					status.setForeground(Theme.danger());
					status.setText("Connect string is required.");
					return;
				}
				result = buildProfile();
				dispose();
			}
		});
		buttons.add(test);
		buttons.add(cancel);
		buttons.add(connect);
		bottom.add(status, BorderLayout.WEST);
		bottom.add(buttons, BorderLayout.EAST);

		setLayout(new BorderLayout());
		add(form, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
		getRootPane().setDefaultButton(connect);
		pack();
		setMinimumSize(new Dimension(560, getHeight()));
		setLocationRelativeTo(owner);
	}

	private void addRow(JPanel form, int row, String label, JComponent field, String hint)
	{
		GridBagConstraints gc = new GridBagConstraints();
		gc.gridx = 0;
		gc.gridy = row;
		gc.anchor = GridBagConstraints.NORTHWEST;
		gc.insets = new Insets(6, 0, 6, 12);
		JLabel l = new JLabel(label);
		form.add(l, gc);

		gc.gridx = 1;
		gc.fill = GridBagConstraints.HORIZONTAL;
		gc.weightx = 1;
		gc.insets = new Insets(6, 0, 6, 0);
		JPanel stack = new JPanel(new BorderLayout(0, 2));
		stack.setOpaque(false);
		stack.add(field, BorderLayout.CENTER);
		if (hint != null)
		{
			JLabel h = new JLabel(hint);
			h.setFont(Theme.uiFont(11));
			h.setForeground(Theme.muted());
			stack.add(h, BorderLayout.SOUTH);
		}
		form.add(stack, gc);
	}

	private ConnectProfile buildProfile()
	{
		if (authTable.isEditing())
		{
			authTable.getCellEditor().stopCellEditing();
		}
		ConnectProfile p = new ConnectProfile();
		p.name = name.getText().trim();
		p.hosts = hosts.getText().trim();
		p.sessionTimeoutMs = ((Number) sessionTimeout.getValue()).intValue();
		p.connectTimeoutMs = ((Number) connectTimeout.getValue()).intValue();
		p.rememberSecrets = remember.isSelected();
		List<ConnectProfile.Auth> auths = new ArrayList<ConnectProfile.Auth>();
		for (int i = 0; i < authModel.getRowCount(); i++)
		{
			String scheme = String.valueOf(authModel.getValueAt(i, 0));
			String id = String.valueOf(authModel.getValueAt(i, 1));
			String secret = String.valueOf(authModel.getValueAt(i, 2));
			if (id != null && !id.trim().isEmpty())
			{
				auths.add(new ConnectProfile.Auth(scheme, id.trim(), secret));
			}
		}
		p.auths = auths;
		return p;
	}

	/** 用独立连接测试，不影响主连接 */
	private void testConnection(final JButton button)
	{
		final ConnectProfile p = buildProfile();
		button.setEnabled(false);
		status.setForeground(Theme.muted());
		status.setText("Testing " + p.hosts + "…");
		Edt.background(new Callable<String>()
		{
			@Override
			public String call()
			{
				ZkConnection probe = new ZkConnection();
				try
				{
					probe.connect(p);
					long session = probe.sessionId();
					probe.close();
					return "OK · session 0x" + Long.toHexString(session);
				}
				catch (Exception e)
				{
					probe.close();
					return "FAILED · " + Inspector.message(e);
				}
			}
		}, new Edt.Callback<String>()
		{
			@Override
			public void accept(String message)
			{
				button.setEnabled(true);
				status.setForeground(message.startsWith("OK") ? Theme.success() : Theme.danger());
				status.setText(message);
			}
		}, null);
	}

	public ConnectProfile result()
	{
		return result;
	}

	/** 密码列平时打点，编辑时才显示明文 */
	private static final class SecretRenderer extends DefaultTableCellRenderer
	{
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
				boolean focus, int row, int col)
		{
			String s = value == null ? "" : String.valueOf(value);
			StringBuilder masked = new StringBuilder();
			for (int i = 0; i < Math.min(s.length(), 12); i++)
			{
				masked.append('•');
			}
			return super.getTableCellRendererComponent(t, masked.toString(), selected, focus, row, col);
		}
	}
}