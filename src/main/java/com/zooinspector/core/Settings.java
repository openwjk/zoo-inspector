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

/** 应用设置，持久化到 ~/.zooinspector/settings.json */
public class Settings
{
	public String theme = "light";
	public int uiFontSize = 13;
	public int monoFontSize = 13;
	/** 搜索结果展示上限 */
	public int resultLimit = 500;
	/** 索引构建的在途请求窗口 */
	public int indexWindow = 256;
	public int maxNodes = 300000;
	public int maxDataBytesPerNode = 64 * 1024;
	/** 连上就自动建路径索引，这样一进来就能搜 */
	public boolean indexOnConnect = true;
	/** 对当前展开的节点挂 watch，变更时局部刷新 */
	public boolean watchExpanded = true;
	public boolean wrapData = true;
	/** 搜索结果归并到 Dubbo 接口层，providers/consumers 点开再看 */
	public boolean groupInterfaces = true;
	public String lastProfile = "";
	public int dividerLocation = 330;

	public IndexBuilder.Options toIndexOptions(boolean withData)
	{
		IndexBuilder.Options o = new IndexBuilder.Options();
		o.window = Math.max(8, indexWindow);
		o.maxNodes = Math.max(1000, maxNodes);
		o.maxDataBytesPerNode = Math.max(1024, maxDataBytesPerNode);
		o.indexData = withData;
		return o;
	}
}