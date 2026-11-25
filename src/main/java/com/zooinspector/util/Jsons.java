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
package com.zooinspector.util;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/** JSON 检测与美化，用于 Data 面板的 JSON 视图 */
public final class Jsons
{
	private Jsons()
	{
	}

	/** 看起来像 JSON 对象/数组才尝试解析，避免把普通文本误判 */
	public static boolean looksLikeJson(String text)
	{
		if (text == null)
		{
			return false;
		}
		String t = text.trim();
		return t.length() > 1 && (t.charAt(0) == '{' || t.charAt(0) == '[');
	}

	/** 美化失败返回 null，调用方回退到原始文本 */
	public static String pretty(String text)
	{
		if (!looksLikeJson(text))
		{
			return null;
		}
		try
		{
			JsonElement el = JsonParser.parseString(text);
			if (!el.isJsonObject() && !el.isJsonArray())
			{
				return null;
			}
			return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
					.toJson(el);
		}
		catch (JsonSyntaxException e)
		{
			return null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** 压缩成单行，便于复制 */
	public static String compact(String text)
	{
		if (!looksLikeJson(text))
		{
			return null;
		}
		try
		{
			JsonElement el = JsonParser.parseString(text);
			return new GsonBuilder().disableHtmlEscaping().create().toJson(el);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}
}