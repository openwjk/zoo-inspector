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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

/**
 * 线程调度工具：所有 ZooKeeper 网络调用都必须在后台线程执行，
 * UI 更新统一回到 EDT。取代老版满屏的匿名 SwingWorker。
 */
public final class Edt
{
	/** 通用后台池，用于零散的读写请求 */
	private static final ExecutorService IO = new ThreadPoolExecutor(2, 16, 60L,
			TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), named("zk-io"));

	/** 索引爬取专用池，线程数由设置决定，重建时创建 */
	private static final AtomicInteger COUNTER = new AtomicInteger();

	private Edt()
	{
	}

	public static ThreadFactory named(final String prefix)
	{
		return new ThreadFactory()
		{
			@Override
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r, prefix + "-" + COUNTER.incrementAndGet());
				t.setDaemon(true);
				return t;
			}
		};
	}

	/** 确保在 EDT 上执行 */
	public static void run(Runnable r)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			r.run();
		}
		else
		{
			SwingUtilities.invokeLater(r);
		}
	}

	public static ExecutorService io()
	{
		return IO;
	}

	public static void submit(Runnable r)
	{
		IO.submit(r);
	}

	/**
	 * 后台执行 task，成功后在 EDT 上回调 onSuccess，失败回调 onError。
	 * onError 为 null 时失败只记录到控制台。
	 */
	public static <T> void background(final Callable<T> task, final Callback<T> onSuccess,
			final Callback<Throwable> onError)
	{
		IO.submit(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					final T result = task.call();
					if (onSuccess != null)
					{
						Edt.run(new Runnable()
						{
							@Override
							public void run()
							{
								onSuccess.accept(result);
							}
						});
					}
				}
				catch (final Throwable t)
				{
					if (onError != null)
					{
						Edt.run(new Runnable()
						{
							@Override
							public void run()
							{
								onError.accept(t);
							}
						});
					}
					else
					{
						t.printStackTrace();
					}
				}
			}
		});
	}

	/** Java 8 环境下替代 java.util.function.Consumer，允许抛检查异常的语义更清晰 */
	public interface Callback<T>
	{
		void accept(T value);
	}
}