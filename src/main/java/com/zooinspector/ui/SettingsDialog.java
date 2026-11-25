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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import com.zooinspector.core.Settings;

/** 设置对话框：主题、字号、索引与搜索上限 */
public class SettingsDialog extends JDialog
{
	private final JComboBox<String> theme = new JComboBox<String>(new String[] { "Light", "Dark" });
	private final JSpinner uiFont = new JSpinner(new SpinnerNumberModel(13, 10, 22, 1));
	private final JSpinner monoFont = new JSpinner(new SpinnerNumberModel(13, 10, 24, 1));
	private final JSpinner resultLimit = new JSpinner(new SpinnerNumberModel(500, 50, 5000, 50));
	private final JSpinner window = new JSpinner(new SpinnerNumberModel(256, 8, 2048, 32));
	private final JSpinner maxNodes = new JSpinner(new SpinnerNumberModel(300000, 1000, 2000000, 10000));
	private final JSpinner maxData = new JSpinner(new SpinnerNumberModel(65536, 1024, 1048576, 1024));
	private final JCheckBox indexOnConnect = new JCheckBox("Build the path index right after connecting");
	private final Settings settings;
	private boolean accepted;

	public SettingsDialog(Window owner, Settings settings)
	{
		super(owner, "Settings", ModalityType.APPLICATION_MODAL);
		this.settings = settings;
		theme.setSelectedItem("dark".equalsIgnoreCase(settings.theme) ? "Dark" : "Light");
		uiFont.setValue(Integer.valueOf(settings.uiFontSize));
		monoFont.setValue(Integer.valueOf(settings.monoFontSize));
		resultLimit.setValue(Integer.valueOf(settings.resultLimit));
		window.setValue(Integer.valueOf(settings.indexWindow));
		maxNodes.setValue(Integer.valueOf(settings.maxNodes));
		maxData.setValue(Integer.valueOf(settings.maxDataBytesPerNode));
		indexOnConnect.setSelected(settings.indexOnConnect);

		JPanel form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(16, 18, 8, 18));
		int row = 0;
		addRow(form, row++, "Theme", theme, null);
		addRow(form, row++, "UI font size", uiFont, null);
		addRow(form, row++, "Monospace size", monoFont, "used for node data and stat values");
		addRow(form, row++, "Search result limit", resultLimit, "hits shown in the list; the total is still counted");
		addRow(form, row++, "Index request window", window, "in-flight requests while crawling; higher is faster, heavier");
		addRow(form, row++, "Index node cap", maxNodes, "stop crawling beyond this many nodes");
		addRow(form, row++, "Value size cap", maxData, "bytes; larger values are skipped by Data search");

		GridBagConstraints gc = new GridBagConstraints();
		gc.gridx = 1;
		gc.gridy = row;
		gc.anchor = GridBagConstraints.WEST;
		gc.insets = new Insets(8, 0, 4, 0);
		form.add(indexOnConnect, gc);

		JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		bottom.setBorder(BorderFactory.createEmptyBorder(6, 18, 14, 18));
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
		JButton ok = new JButton("Apply", Icons.get("check"));
		ok.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				apply();
				accepted = true;
				dispose();
			}
		});
		bottom.add(cancel);
		bottom.add(ok);

		setLayout(new BorderLayout());
		add(form, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
		getRootPane().setDefaultButton(ok);
		pack();
		setLocationRelativeTo(owner);
	}

	private void addRow(JPanel form, int row, String label, JComponent field, String hint)
	{
		GridBagConstraints gc = new GridBagConstraints();
		gc.gridx = 0;
		gc.gridy = row;
		gc.anchor = GridBagConstraints.NORTHWEST;
		gc.insets = new Insets(6, 0, 6, 14);
		form.add(new JLabel(label), gc);

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

	private void apply()
	{
		settings.theme = "Dark".equals(theme.getSelectedItem()) ? "dark" : "light";
		settings.uiFontSize = ((Number) uiFont.getValue()).intValue();
		settings.monoFontSize = ((Number) monoFont.getValue()).intValue();
		settings.resultLimit = ((Number) resultLimit.getValue()).intValue();
		settings.indexWindow = ((Number) window.getValue()).intValue();
		settings.maxNodes = ((Number) maxNodes.getValue()).intValue();
		settings.maxDataBytesPerNode = ((Number) maxData.getValue()).intValue();
		settings.indexOnConnect = indexOnConnect.isSelected();
	}

	public boolean accepted()
	{
		return accepted;
	}
}