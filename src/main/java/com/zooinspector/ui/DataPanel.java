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
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.concurrent.Callable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;

import com.zooinspector.core.Inspector;
import com.zooinspector.core.NodeSnapshot;
import com.zooinspector.core.ZkConnection;
import com.zooinspector.util.Edt;
import com.zooinspector.util.HexDump;
import com.zooinspector.util.Jsons;

/** Data 面板：Text / JSON / Hex / Base64 四视图，数据内查找，带版本校验的保存 */
public class DataPanel extends JPanel
{
	private enum View
	{
		TEXT("Text"), JSON("JSON"), HEX("Hex"), BASE64("Base64");

		final String label;

		View(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	private static final Charset UTF8 = Charset.forName("UTF-8");
	/** 超过这个大小就不做 JSON 美化，避免卡住 EDT */
	private static final int PRETTY_LIMIT = 512 * 1024;

	private final Inspector inspector;
	private final JTextArea area = new JTextArea();
	private final JComboBox<View> viewBox = new JComboBox<View>(View.values());
	private final JCheckBox wrap = new JCheckBox("Wrap", true);
	private final JButton save = new JButton("Save", Icons.get("save"));
	private final JButton reload = new JButton(Icons.get("refresh"));
	private final JTextField find = new JTextField(14);
	private final JLabel findInfo = new JLabel("");
	private final JLabel info = new JLabel(" ");
	private final JLabel dirtyMark = new JLabel("");

	private String path;
	private byte[] raw = new byte[0];
	private NodeSnapshot snapshot;
	private boolean dirty;
	private boolean rendering;
	/** 用户手动切过视图后就不再自动选 JSON */
	private boolean userViewOverride;
	private boolean adjustingView;
	private int findIndex = -1;
	private int findCount;

	public DataPanel(Inspector inspector)
	{
		super(new BorderLayout());
		this.inspector = inspector;

		area.setFont(Theme.monoFont(inspector.settings().monoFontSize));
		area.setTabSize(2);
		area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		area.setLineWrap(true);
		area.setWrapStyleWord(false);
		area.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				markDirty();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				markDirty();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				markDirty();
			}
		});

		JScrollPane scroll = new JScrollPane(area);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		add(scroll, BorderLayout.CENTER);
		add(buildToolbar(), BorderLayout.NORTH);
		add(buildFooter(), BorderLayout.SOUTH);
		setEnabledState(false);
	}

	private JPanel buildToolbar()
	{
		JPanel bar = new JPanel(new BorderLayout(8, 0));
		bar.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));

		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		left.setOpaque(false);
		viewBox.setPreferredSize(new Dimension(96, 28));
		viewBox.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				if (!adjustingView)
				{
					userViewOverride = true;
				}
				render();
			}
		});
		wrap.setOpaque(false);
		wrap.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				area.setLineWrap(wrap.isSelected());
				area.revalidate();
			}
		});
		left.add(viewBox);
		left.add(wrap);
		left.add(dirtyMark);

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		right.setOpaque(false);
		find.putClientProperty("JTextField.placeholderText", "Find in data");
		find.putClientProperty("JTextField.leadingIcon", Icons.get("search"));
		find.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ENTER)
				{
					nextMatch();
				}
				else if (e.getKeyCode() == KeyEvent.VK_ESCAPE)
				{
					find.setText("");
					clearHighlights();
					area.requestFocusInWindow();
				}
			}
		});
		find.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				highlightMatches();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				highlightMatches();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				highlightMatches();
			}
		});
		findInfo.setFont(Theme.uiFont(11));
		findInfo.setForeground(Theme.muted());
		save.setToolTipText("Save data (Ctrl+S)");
		save.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				saveNow();
			}
		});
		reload.setToolTipText("Reload from server");
		reload.putClientProperty("JButton.buttonType", "borderless");
		reload.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				if (path != null)
				{
					load(path, true);
				}
			}
		});
		right.add(find);
		right.add(findInfo);
		right.add(reload);
		right.add(save);

		bar.add(left, BorderLayout.WEST);
		bar.add(right, BorderLayout.EAST);
		return bar;
	}

	private JPanel buildFooter()
	{
		JPanel footer = new JPanel(new BorderLayout());
		footer.setBorder(BorderFactory.createEmptyBorder(4, 12, 6, 12));
		info.setFont(Theme.uiFont(11));
		info.setForeground(Theme.muted());
		footer.add(info, BorderLayout.WEST);
		return footer;
	}

	public void focusEditor()
	{
		area.requestFocusInWindow();
	}

	public void focusFind()
	{
		find.requestFocusInWindow();
		find.selectAll();
	}

	public boolean hasUnsavedChanges()
	{
		return dirty;
	}

	/** 切换节点前的未保存确认；返回 false 表示用户取消 */
	public boolean confirmDiscard()
	{
		if (!dirty)
		{
			return true;
		}
		int answer = JOptionPane.showConfirmDialog(this,
				"Data for " + path + " has unsaved changes. Discard them?", "Unsaved changes",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		return answer == JOptionPane.YES_OPTION;
	}

	public void clear()
	{
		path = null;
		raw = new byte[0];
		snapshot = null;
		rendering = true;
		area.setText("");
		rendering = false;
		dirty = false;
		updateDirtyMark();
		info.setText(" ");
		setEnabledState(false);
	}

	public void load(final String nodePath, final boolean force)
	{
		if (!force && nodePath.equals(this.path))
		{
			return;
		}
		this.path = nodePath;
		Edt.background(new Callable<ZkConnection.Data>()
		{
			@Override
			public ZkConnection.Data call() throws Exception
			{
				return inspector.connection().data(nodePath);
			}
		}, new Edt.Callback<ZkConnection.Data>()
		{
			@Override
			public void accept(ZkConnection.Data data)
			{
				if (!nodePath.equals(path))
				{
					return;
				}
				raw = data.bytes;
				snapshot = data.snapshot;
				dirty = false;
				autoSelectView();
				render();
				updateDirtyMark();
				setEnabledState(true);
			}
		}, new Edt.Callback<Throwable>()
		{
			@Override
			public void accept(Throwable t)
			{
				rendering = true;
				area.setText("");
				rendering = false;
				dirty = false;
				updateDirtyMark();
				setEnabledState(false);
				info.setText(Inspector.message(t));
				info.setForeground(Theme.danger());
			}
		});
	}

	private void setEnabledState(boolean enabled)
	{
		area.setEnabled(enabled);
		viewBox.setEnabled(enabled);
		wrap.setEnabled(enabled);
		find.setEnabled(enabled);
		reload.setEnabled(enabled);
		save.setEnabled(enabled && dirty && isEditable());
	}

	private boolean isEditable()
	{
		View v = (View) viewBox.getSelectedItem();
		return v == View.TEXT || v == View.JSON;
	}

	/** 看起来是 JSON 就默认给美化视图，配置类节点一打开就可读 */
	private void autoSelectView()
	{
		if (userViewOverride)
		{
			return;
		}
		boolean json = raw.length > 0 && raw.length <= PRETTY_LIMIT
				&& Jsons.looksLikeJson(new String(raw, UTF8));
		adjustingView = true;
		viewBox.setSelectedItem(json ? View.JSON : View.TEXT);
		adjustingView = false;
	}

	private void render()
	{
		rendering = true;
		View v = (View) viewBox.getSelectedItem();
		String text;
		switch (v == null ? View.TEXT : v)
		{
			case JSON:
				String original = new String(raw, UTF8);
				String pretty = raw.length <= PRETTY_LIMIT ? Jsons.pretty(original) : null;
				text = pretty != null ? pretty : original;
				break;
			case HEX:
				text = HexDump.format(raw);
				break;
			case BASE64:
				text = raw.length == 0 ? "" : Base64.getEncoder().encodeToString(raw);
				break;
			default:
				text = new String(raw, UTF8);
				break;
		}
		area.setText(text);
		area.setCaretPosition(0);
		area.setEditable(isEditable());
		rendering = false;
		dirty = false;
		updateDirtyMark();
		updateInfo();
		highlightMatches();
		save.setEnabled(dirty && isEditable());
	}

	private void updateInfo()
	{
		StringBuilder sb = new StringBuilder();
		sb.append(TreePanel.formatSize(raw.length));
		if (raw.length == 0)
		{
			sb.append("  ·  this node stores no data");
		}
		if (snapshot != null)
		{
			sb.append("  ·  version ").append(snapshot.version);
			if (snapshot.isEphemeral())
			{
				sb.append("  ·  ephemeral");
			}
		}
		View v = (View) viewBox.getSelectedItem();
		if (v == View.HEX || v == View.BASE64)
		{
			sb.append("  ·  read-only view");
		}
		else if (v == View.JSON && raw.length > 0 && raw.length <= PRETTY_LIMIT
				&& Jsons.looksLikeJson(new String(raw, UTF8))
				&& Jsons.pretty(new String(raw, UTF8)) == null)
		{
			sb.append("  ·  not valid JSON, showing raw text");
		}
		if (raw.length > PRETTY_LIMIT)
		{
			sb.append("  ·  large value, JSON formatting skipped");
		}
		sb.append("  ·  UTF-8");
		info.setForeground(Theme.muted());
		info.setText(sb.toString());
	}

	private void markDirty()
	{
		if (rendering || !isEditable())
		{
			return;
		}
		dirty = true;
		updateDirtyMark();
		save.setEnabled(true);
	}

	private void updateDirtyMark()
	{
		dirtyMark.setText(dirty ? "● unsaved" : "");
		dirtyMark.setForeground(Theme.warn());
		dirtyMark.setFont(Theme.uiFont(11));
	}

	public void saveNow()
	{
		if (path == null || !dirty || !isEditable())
		{
			return;
		}
		final byte[] bytes = area.getText().getBytes(UTF8);
		final int version = snapshot == null ? -1 : snapshot.version;
		final String target = path;
		save.setEnabled(false);
		inspector.saveData(target, bytes, version, new Inspector.Done()
		{
			@Override
			public void done(String error)
			{
				if (error != null)
				{
					save.setEnabled(true);
					Toasts.error(DataPanel.this, error);
					return;
				}
				raw = bytes;
				dirty = false;
				updateDirtyMark();
				Toasts.success(DataPanel.this, "Saved " + target);
				load(target, true);
			}
		});
	}

	// ---------------- 数据内查找 ----------------

	private void clearHighlights()
	{
		area.getHighlighter().removeAllHighlights();
		findInfo.setText("");
		findCount = 0;
		findIndex = -1;
	}

	private void highlightMatches()
	{
		clearHighlights();
		String needle = find.getText();
		if (needle == null || needle.isEmpty())
		{
			return;
		}
		String text = area.getText();
		String lowerText = text.toLowerCase(java.util.Locale.ROOT);
		String lowerNeedle = needle.toLowerCase(java.util.Locale.ROOT);
		Highlighter highlighter = area.getHighlighter();
		Color color = Theme.mix(Theme.accent(), Theme.background(), 0.35f);
		int at = lowerText.indexOf(lowerNeedle);
		int first = -1;
		while (at >= 0 && findCount < 5000)
		{
			try
			{
				highlighter.addHighlight(at, at + needle.length(),
						new DefaultHighlighter.DefaultHighlightPainter(color));
			}
			catch (BadLocationException e)
			{
				break;
			}
			if (first < 0)
			{
				first = at;
			}
			findCount++;
			at = lowerText.indexOf(lowerNeedle, at + needle.length());
		}
		findInfo.setText(findCount == 0 ? "no match" : findCount + (findCount >= 5000 ? "+" : ""));
		if (first >= 0)
		{
			findIndex = 0;
			scrollTo(first, needle.length());
		}
	}

	private void nextMatch()
	{
		String needle = find.getText();
		if (needle == null || needle.isEmpty() || findCount == 0)
		{
			return;
		}
		String lowerText = area.getText().toLowerCase(java.util.Locale.ROOT);
		String lowerNeedle = needle.toLowerCase(java.util.Locale.ROOT);
		int caret = area.getCaretPosition();
		int at = lowerText.indexOf(lowerNeedle, Math.max(caret, 0));
		if (at < 0)
		{
			at = lowerText.indexOf(lowerNeedle);
		}
		if (at >= 0)
		{
			scrollTo(at, needle.length());
		}
	}

	private void scrollTo(int start, int length)
	{
		try
		{
			java.awt.Rectangle r = area.modelToView(start);
			if (r != null)
			{
				area.scrollRectToVisible(new java.awt.Rectangle(r.x, Math.max(0, r.y - 60),
						r.width, r.height + 120));
			}
			area.setCaretPosition(start + length);
			area.moveCaretPosition(start);
		}
		catch (BadLocationException e)
		{
			// ignore
		}
	}

	@Override
	public void updateUI()
	{
		super.updateUI();
		if (area != null && inspector != null)
		{
			area.setFont(Theme.monoFont(inspector.settings().monoFontSize));
			info.setForeground(Theme.muted());
			findInfo.setForeground(Theme.muted());
			find.putClientProperty("JTextField.leadingIcon", Icons.get("search"));
			save.setIcon(Icons.get("save"));
			reload.setIcon(Icons.get("refresh"));
		}
	}
}