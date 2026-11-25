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

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.zooinspector.util.Edt;
import com.zooinspector.util.ZkPaths;

/**
 * 全量索引构建器：BFS + 异步流水线。
 *
 * <p>不用多线程同步调用，而是把请求按 in-flight 窗口批量压进同一条 ZooKeeper 连接，
 * 请求在 wire 上流水线化，一次往返带回大批结果 —— 比线程池逐个同步等待快得多，
 * 也不会给集群开一堆会话。所有回调在 ZooKeeper 的 EventThread 上串行执行，
 * 因此数组追加天然单线程，无需加锁。
 *
 * <p>默认只爬路径（一次 getChildren 同时拿到 stat），不读任何节点 data；
 * 只有勾选 Data 时才追加一轮 getData，并受单节点与总量双重上限约束。
 */
public class IndexBuilder
{
	public static final class Options
	{
		/** 爬取起点，默认整棵树；局部重建时指向某个子树 */
		public String rootPath = ZkPaths.ROOT;
		/** 同时在途的请求数 */
		public int window = 256;
		/** 索引节点数上限，防止超大集群把内存吃光 */
		public int maxNodes = 300000;
		public boolean indexData = false;
		/** 单节点 data 超过这个字节数就跳过内容索引 */
		public int maxDataBytesPerNode = 64 * 1024;
		/** 每节点最多保留的字符数 */
		public int perNodeChars = 8192;
		/** 内容索引总字符预算（约 2 字节/字符） */
		public int dataBudgetChars = 64 * 1024 * 1024;
	}

	public interface Listener
	{
		void progress(int indexed, int pending, String phase);

		void finished(PathIndex index, long elapsedMs, String note);
	}

	private static final Charset UTF8 = Charset.forName("UTF-8");

	private final ZkConnection conn;
	private final Options opts;
	private final Object lock = new Object();
	private final Deque<String> queue = new ArrayDeque<String>();
	private final Deque<Integer> dataQueue = new ArrayDeque<Integer>();

	private String[] paths = new String[4096];
	private String[] lower = new String[4096];
	private int[] nameStart = new int[4096];
	private int[] numChildren = new int[4096];
	private int[] dataLength = new int[4096];
	private boolean[] ephemeral = new boolean[4096];
	private byte[] categories = new byte[4096];
	private String[] lowerData;

	private volatile int size;
	private int errors;
	private int dataSkipped;
	private long dataChars;
	private int inFlight;
	private boolean dataPhase;
	private volatile boolean cancelled;
	private volatile boolean finished;
	private boolean capped;

	private long startedAt;
	private Listener listener;
	private ScheduledExecutorService ticker;
	private ScheduledFuture<?> tickTask;

	public IndexBuilder(ZkConnection conn, Options opts)
	{
		this.conn = conn;
		this.opts = opts == null ? new Options() : opts;
	}

	public void start(Listener listener)
	{
		this.listener = listener;
		this.startedAt = System.nanoTime();
		queue.add(ZkPaths.normalize(opts.rootPath));
		ticker = Executors.newSingleThreadScheduledExecutor(Edt.named("index-progress"));
		tickTask = ticker.scheduleAtFixedRate(new Runnable()
		{
			@Override
			public void run()
			{
				publishProgress();
			}
		}, 200, 200, TimeUnit.MILLISECONDS);
		synchronized (lock)
		{
			pump();
		}
	}

	public void cancel()
	{
		cancelled = true;
		synchronized (lock)
		{
			queue.clear();
			dataQueue.clear();
			if (inFlight == 0)
			{
				finish("cancelled");
			}
		}
	}

	public boolean isFinished()
	{
		return finished;
	}

	private void publishProgress()
	{
		final int indexed = size;
		final int pending;
		final String phase;
		synchronized (lock)
		{
			pending = (dataPhase ? dataQueue.size() : queue.size()) + inFlight;
			phase = dataPhase ? "indexing data" : "crawling paths";
		}
		final Listener l = listener;
		if (l != null && !finished)
		{
			Edt.run(new Runnable()
			{
				@Override
				public void run()
				{
					l.progress(indexed, pending, phase);
				}
			});
		}
	}

	/** 调用方持有 lock */
	private void pump()
	{
		if (finished)
		{
			return;
		}
		if (!dataPhase)
		{
			while (!cancelled && inFlight < opts.window && !queue.isEmpty() && size < opts.maxNodes)
			{
				String p = queue.poll();
				inFlight++;
				if (!conn.getChildrenAsync(p, PATH_CALLBACK))
				{
					inFlight--;
					errors++;
				}
			}
			if (size >= opts.maxNodes && !queue.isEmpty())
			{
				capped = true;
				queue.clear();
			}
			if (inFlight == 0 && queue.isEmpty())
			{
				startDataPhaseOrFinish();
			}
			return;
		}
		while (!cancelled && inFlight < opts.window && !dataQueue.isEmpty()
				&& dataChars < opts.dataBudgetChars)
		{
			Integer idx = dataQueue.poll();
			inFlight++;
			if (!conn.getDataAsync(paths[idx.intValue()], DATA_CALLBACK))
			{
				inFlight--;
				errors++;
			}
		}
		if (dataChars >= opts.dataBudgetChars && !dataQueue.isEmpty())
		{
			dataSkipped += dataQueue.size();
			dataQueue.clear();
		}
		if (inFlight == 0 && dataQueue.isEmpty())
		{
			finish(null);
		}
	}

	private final ZkConnection.ChildrenCallback PATH_CALLBACK = new ZkConnection.ChildrenCallback()
	{
		@Override
		public void done(String path, java.util.List<String> names, NodeSnapshot snapshot,
				Throwable error)
		{
			synchronized (lock)
			{
				inFlight--;
				if (error != null || names == null)
				{
					errors++;
				}
				else
				{
					append(path, snapshot, Dubbo.flagsOf(names));
					for (String name : names)
					{
						queue.add(ZkPaths.join(path, name));
					}
				}
				pump();
			}
		}
	};

	private final ZkConnection.DataCallback DATA_CALLBACK = new ZkConnection.DataCallback()
	{
		@Override
		public void done(String path, byte[] bytes, NodeSnapshot snapshot, Throwable error)
		{
			synchronized (lock)
			{
				inFlight--;
				if (error != null)
				{
					errors++;
				}
				else if (bytes != null && bytes.length > 0)
				{
					int idx = findIndex(path);
					if (idx >= 0)
					{
						String text = new String(bytes, UTF8);
						if (text.length() > opts.perNodeChars)
						{
							text = text.substring(0, opts.perNodeChars);
						}
						String lo = text.toLowerCase(Locale.ROOT);
						lowerData[idx] = lo;
						dataChars += lo.length();
					}
				}
				pump();
			}
		}
	};

	/** 内容回调用路径反查下标；节点数不多时线性扫可接受，故建一次映射 */
	private java.util.Map<String, Integer> pathToIndex;

	private int findIndex(String path)
	{
		Integer i = pathToIndex.get(path);
		return i == null ? -1 : i.intValue();
	}

	private void append(String path, NodeSnapshot snap, byte categoryFlags)
	{
		if (size >= paths.length)
		{
			int cap = Math.max(8192, paths.length * 2);
			paths = Arrays.copyOf(paths, cap);
			lower = Arrays.copyOf(lower, cap);
			nameStart = Arrays.copyOf(nameStart, cap);
			numChildren = Arrays.copyOf(numChildren, cap);
			dataLength = Arrays.copyOf(dataLength, cap);
			ephemeral = Arrays.copyOf(ephemeral, cap);
			categories = Arrays.copyOf(categories, cap);
		}
		String p = ZkPaths.normalize(path);
		paths[size] = p;
		// 纯小写路径时 toLowerCase 返回自身，与 paths 共享同一个 String
		lower[size] = p.toLowerCase(Locale.ROOT);
		nameStart[size] = p.lastIndexOf('/') + 1;
		numChildren[size] = snap == null ? 0 : snap.numChildren;
		dataLength[size] = snap == null ? 0 : snap.dataLength;
		ephemeral[size] = snap != null && snap.isEphemeral();
		categories[size] = categoryFlags;
		size++;
	}

	/** 调用方持有 lock */
	private void startDataPhaseOrFinish()
	{
		if (!opts.indexData || cancelled)
		{
			finish(cancelled ? "cancelled" : null);
			return;
		}
		dataPhase = true;
		lowerData = new String[size];
		pathToIndex = new java.util.HashMap<String, Integer>(Math.max(16, size * 2));
		for (int i = 0; i < size; i++)
		{
			pathToIndex.put(paths[i], i);
			int len = dataLength[i];
			if (len <= 0)
			{
				continue;
			}
			if (len > opts.maxDataBytesPerNode)
			{
				dataSkipped++;
				continue;
			}
			dataQueue.add(Integer.valueOf(i));
		}
		pump();
	}

	/** 调用方持有 lock */
	private void finish(String note)
	{
		if (finished)
		{
			return;
		}
		finished = true;
		if (tickTask != null)
		{
			tickTask.cancel(false);
		}
		if (ticker != null)
		{
			ticker.shutdown();
		}
		StringBuilder sb = new StringBuilder();
		if (capped)
		{
			sb.append("stopped at the ").append(opts.maxNodes).append(" node cap");
		}
		if (errors > 0)
		{
			if (sb.length() > 0)
			{
				sb.append("; ");
			}
			sb.append(errors).append(" node(s) unreadable");
		}
		if (dataSkipped > 0)
		{
			if (sb.length() > 0)
			{
				sb.append("; ");
			}
			sb.append(dataSkipped).append(" value(s) skipped (size/budget)");
		}
		if (note != null)
		{
			if (sb.length() > 0)
			{
				sb.append("; ");
			}
			sb.append(note);
		}
		final PathIndex index = new PathIndex(Arrays.copyOf(paths, size), Arrays.copyOf(lower, size),
				Arrays.copyOf(nameStart, size), Arrays.copyOf(numChildren, size),
				Arrays.copyOf(dataLength, size), Arrays.copyOf(ephemeral, size),
				Arrays.copyOf(categories, size), lowerData, size, dataPhase, errors,
				System.currentTimeMillis(), dataSkipped);
		final long elapsed = (System.nanoTime() - startedAt) / 1000000L;
		final String message = sb.length() == 0 ? null : sb.toString();
		final Listener l = listener;
		// 释放构建期的大对象引用
		queue.clear();
		dataQueue.clear();
		if (pathToIndex != null)
		{
			pathToIndex.clear();
		}
		if (l != null)
		{
			Edt.run(new Runnable()
			{
				@Override
				public void run()
				{
					l.finished(index, elapsed, message);
				}
			});
		}
	}
}