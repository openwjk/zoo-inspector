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

import java.awt.Component;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.TableCellRenderer;

/** 表格外观小工具 */
final class Ui
{
	private Ui()
	{
	}

	/**
	 * 表头与内容对齐方式保持一致（Swing 默认表头居中，和左对齐的正文对不上）。
	 * 包一层原有 renderer，保留 FlatLaf 的表头样式与排序箭头。
	 */
	static void alignHeaders(JTable table, final int... rightAligned)
	{
		final TableCellRenderer original = table.getTableHeader().getDefaultRenderer();
		table.getTableHeader().setDefaultRenderer(new TableCellRenderer()
		{
			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
					boolean isSelected, boolean hasFocus, int row, int column)
			{
				Component c = original.getTableCellRendererComponent(t, value, isSelected, hasFocus,
						row, column);
				if (c instanceof JLabel)
				{
					int modelColumn = t.convertColumnIndexToModel(column);
					boolean right = false;
					for (int r : rightAligned)
					{
						if (r == modelColumn)
						{
							right = true;
							break;
						}
					}
					((JLabel) c).setHorizontalAlignment(right ? SwingConstants.RIGHT
							: SwingConstants.LEFT);
				}
				return c;
			}
		});
	}
}