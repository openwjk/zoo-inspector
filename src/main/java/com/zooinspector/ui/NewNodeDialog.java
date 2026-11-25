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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.charset.Charset;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import org.apache.zookeeper.CreateMode;

import com.zooinspector.util.ZkPaths;

/** 新建节点对话框：名字、类型、初始 data */
public class NewNodeDialog extends JDialog
{
	private static final Charset UTF8 = Charset.forName("UTF-8");

	private final JTextField name = new JTextField(24);
	private final JComboBox<ModeItem> mode = new JComboBox<ModeItem>(new ModeItem[] {
			new ModeItem("Persistent", CreateMode.PERSISTENT),
			new ModeItem("Persistent sequential", CreateMode.PERSISTENT_SEQUENTIAL),
			new ModeItem("Ephemeral", CreateMode.EPHEMERAL),
			new ModeItem("Ephemeral sequential", CreateMode.EPHEMERAL_SEQUENTIAL) });
	private final JTextArea data = new JTextArea(6, 24);
	private final JLabel error = new JLabel(" ");
	private boolean accepted;

	public NewNodeDialog(Window owner, String parentPath)
	{
		super(owner, "New node under " + parentPath, ModalityType.APPLICATION_MODAL);
		JPanel form = new JPanel(new java.awt.GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(16, 18, 6, 18));
		java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
		gc.gridx = 0;
		gc.gridy = 0;
		gc.anchor = java.awt.GridBagConstraints.NORTHWEST;
		gc.insets = new java.awt.Insets(4, 0, 8, 12);
		form.add(new JLabel("Name"), gc);
		gc.gridx = 1;
		gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
		gc.weightx = 1;
		form.add(name, gc);

		gc.gridx = 0;
		gc.gridy = 1;
		gc.fill = java.awt.GridBagConstraints.NONE;
		gc.weightx = 0;
		form.add(new JLabel("Type"), gc);
		gc.gridx = 1;
		gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
		gc.weightx = 1;
		form.add(mode, gc);

		gc.gridx = 0;
		gc.gridy = 2;
		gc.fill = java.awt.GridBagConstraints.NONE;
		gc.weightx = 0;
		form.add(new JLabel("Data"), gc);
		gc.gridx = 1;
		gc.fill = java.awt.GridBagConstraints.BOTH;
		gc.weightx = 1;
		gc.weighty = 1;
		data.setFont(Theme.monoFont(13));
		data.setLineWrap(true);
		JScrollPane scroll = new JScrollPane(data);
		scroll.setPreferredSize(new Dimension(320, 110));
		form.add(scroll, gc);

		error.setFont(Theme.uiFont(11));
		error.setForeground(Theme.danger());
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setBorder(BorderFactory.createEmptyBorder(4, 18, 14, 18));
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				accepted = false;
				dispose();
			}
		});
		JButton create = new JButton("Create", Icons.get("plus"));
		create.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				String problem = ZkPaths.validateName(name.getText().trim());
				if (problem != null)
				{
					error.setText(problem);
					return;
				}
				accepted = true;
				dispose();
			}
		});
		buttons.add(cancel);
		buttons.add(create);
		bottom.add(error, BorderLayout.WEST);
		bottom.add(buttons, BorderLayout.EAST);

		setLayout(new BorderLayout());
		add(form, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
		getRootPane().setDefaultButton(create);
		pack();
		setLocationRelativeTo(owner);
	}

	public boolean accepted()
	{
		return accepted;
	}

	public String nodeName()
	{
		return name.getText().trim();
	}

	public CreateMode createMode()
	{
		ModeItem item = (ModeItem) mode.getSelectedItem();
		return item == null ? CreateMode.PERSISTENT : item.mode;
	}

	public byte[] nodeData()
	{
		return data.getText().getBytes(UTF8);
	}

	private static final class ModeItem
	{
		final String label;
		final CreateMode mode;

		ModeItem(String label, CreateMode mode)
		{
			this.label = label;
			this.mode = mode;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}
}