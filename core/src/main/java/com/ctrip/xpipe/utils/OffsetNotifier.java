package com.ctrip.xpipe.utils;

import java.util.concurrent.locks.AbstractQueuedLongSynchronizer;

/**
 * @author marsqing
 *
 *         May 20, 2016 2:06:20 PM
 */
public class OffsetNotifier {
	private static final class Sync extends AbstractQueuedLongSynchronizer {
		private static final long serialVersionUID = 4982264981922014374L;

		Sync(long offset) {
			setState(offset);
		}

		@Override
		protected long tryAcquireShared(long startOffset) {
			return (getState() >= startOffset) ? 1 : -1;
		}

		@Override
		protected boolean tryReleaseShared(long newOffset) {
			setState(newOffset);
			return true;
		}
	}

	private final Sync syncFast;
	private final Sync syncSlow;

	public OffsetNotifier(long offset) {
		this.syncFast = new Sync(offset);
		this.syncSlow = new Sync(offset);
	}

	public void await(long startOffset) throws InterruptedException {
		syncFast.acquireSharedInterruptibly(startOffset);
	}

	public void awaitSlowQueue(long startOffset) throws InterruptedException {
		syncFast.acquireSharedInterruptibly(startOffset);
	}

	public boolean await(long startOffset, long miliSeconds) throws InterruptedException{
		return syncFast.tryAcquireSharedNanos(startOffset, miliSeconds * (1000*1000));

	}
	
	public boolean awaitSlowQueue(long startOffset, long miliSeconds) throws InterruptedException{
		return syncSlow.tryAcquireSharedNanos(startOffset, miliSeconds * (1000*1000));
		
	}

	public void offsetIncreased(long newOffset) {
		syncFast.releaseShared(newOffset);
		syncSlow.releaseShared(newOffset);
	}
}
