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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.zooinspector.util.Edt;

/**
 * 搜索调度：输入防抖 + 过期查询丢弃 + 结果增量发布。
 *
 * <p>查询跑在单独一条线程上，EDT 只负责渲染；每次输入递增 generation，
 * 旧查询在扫描过程中自行退出，不会出现「输完了还在算上一个词」的卡顿。
 */
public class SearchEngine
{
	public interface Listener
	{
		void results(PathIndex.Result result, String query, PathIndex.Mode mode);
	}

	private static final long DEBOUNCE_MS = 80;

	private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor(
			Edt.named("search-debounce"));
	private final ExecutorService worker = Executors.newSingleThreadExecutor(
			Edt.named("search-worker"));
	private final AtomicLong generation = new AtomicLong();

	private volatile PathIndex index = PathIndex.EMPTY;
	private volatile Listener listener;
	private volatile ScheduledFuture<?> pending;
	private volatile String lastQuery = "";
	private volatile PathIndex.Mode lastMode = PathIndex.Mode.CONTAINS;
	private volatile int limit = 500;
	private volatile boolean groupInterfaces = true;

	public void setGroupInterfaces(boolean group)
	{
		this.groupInterfaces = group;
	}

	public boolean isGroupInterfaces()
	{
		return groupInterfaces;
	}

	public void setListener(Listener l)
	{
		this.listener = l;
	}

	public void setLimit(int limit)
	{
		this.limit = Math.max(10, limit);
	}

	public PathIndex index()
	{
		return index;
	}

	/** 换索引后自动重跑当前查询，让结果跟着刷新 */
	public void setIndex(PathIndex newIndex)
	{
		this.index = newIndex == null ? PathIndex.EMPTY : newIndex;
		if (!lastQuery.isEmpty())
		{
			query(lastQuery, lastMode);
		}
	}

	public void query(final String text, final PathIndex.Mode mode)
	{
		this.lastQuery = text == null ? "" : text;
		this.lastMode = mode == null ? PathIndex.Mode.CONTAINS : mode;
		ScheduledFuture<?> prev = pending;
		if (prev != null)
		{
			prev.cancel(false);
		}
		if (lastQuery.isEmpty())
		{
			generation.incrementAndGet();
			publish(new PathIndex.Result(java.util.Collections.<PathIndex.Hit> emptyList(), 0, false,
					0, null), "", lastMode);
			return;
		}
		final String q = lastQuery;
		final PathIndex.Mode m = lastMode;
		pending = debounce.schedule(new Runnable()
		{
			@Override
			public void run()
			{
				runQuery(q, m);
			}
		}, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
	}

	/** 立即执行（切换模式、回车时用），跳过防抖 */
	public void queryNow(String text, PathIndex.Mode mode)
	{
		this.lastQuery = text == null ? "" : text;
		this.lastMode = mode == null ? PathIndex.Mode.CONTAINS : mode;
		ScheduledFuture<?> prev = pending;
		if (prev != null)
		{
			prev.cancel(false);
		}
		runQuery(lastQuery, lastMode);
	}

	private void runQuery(final String q, final PathIndex.Mode mode)
	{
		final long gen = generation.incrementAndGet();
		final PathIndex snapshot = index;
		final int cap = limit;
		final boolean group = groupInterfaces;
		worker.submit(new Runnable()
		{
			@Override
			public void run()
			{
				if (generation.get() != gen)
				{
					return;
				}
				PathIndex.Result result;
				try
				{
					result = snapshot.search(q, mode, cap, new java.util.function.BooleanSupplier()
					{
						@Override
						public boolean getAsBoolean()
						{
							return generation.get() != gen;
						}
					}, new PathIndex.Publisher()
					{
						@Override
						public void publish(PathIndex.Result partial)
						{
							if (generation.get() == gen)
							{
								SearchEngine.this.publish(partial, q, mode);
							}
						}
					}, group);
				}
				catch (RuntimeException e)
				{
					result = new PathIndex.Result(java.util.Collections.<PathIndex.Hit> emptyList(),
							0, false, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
				}
				if (result != null && generation.get() == gen)
				{
					publish(result, q, mode);
				}
			}
		});
	}

	private void publish(final PathIndex.Result result, final String q, final PathIndex.Mode mode)
	{
		final Listener l = listener;
		if (l == null)
		{
			return;
		}
		Edt.run(new Runnable()
		{
			@Override
			public void run()
			{
				l.results(result, q, mode);
			}
		});
	}

	public void shutdown()
	{
		debounce.shutdownNow();
		worker.shutdownNow();
	}
}