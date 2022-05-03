/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    //表示ByteBuffer最小的容量，默认为64，也就是无论ByteBuffer在怎么缩容，容量也不会低于64。
    static final int DEFAULT_MINIMUM = 64;
    // Use an initial value that is bigger than the common MTU of 1500
//    表示ByteBuffer的初始化容量。默认为2048
    static final int DEFAULT_INITIAL = 2048;
    //表示ByteBuffer的最大容量，默认为65536，也就是无论ByteBuffer在怎么扩容，容量也不会超过65536
    static final int DEFAULT_MAXIMUM = 65536;

    //扩容步长
    private static final int INDEX_INCREMENT = 4;

   //缩容步长
    private static final int INDEX_DECREMENT = 1;

    //RecvBuf分配容量表（扩缩容索引表）按照表中记录的容量大小进行扩缩容
    private static final int[] SIZE_TABLE;

    //扩容：
    //当对容量为2048的ByteBuffer进行扩容时，根据当前的容量索引index = 33 加上 扩容步长INDEX_INCREMENT = 4计算出扩容后的容量索引为37，
    // 那么扩缩容索引表SIZE_TABLE下标37对应的容量就是本次ByteBuffer扩容后的容量SIZE_TABLE[37] = 32768
    //缩容：
    //同理对容量为2048的ByteBuffer进行缩容时，我们就需要用当前容量索引index = 33 减去 缩容步长INDEX_DECREMENT = 1计算出缩容后的容量索引32，
    // 那么扩缩容索引表SIZE_TABLE下标32对应的容量就是本次ByteBuffer缩容后的容量SIZE_TABLE[32] = 1024
    static {
        List<Integer> sizeTable = new ArrayList<Integer>();

        //当分配容量小于512时，扩容单位为16递增
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        // Suppress a warning since i becomes negative when an integer overflow happens
        //当分配容量大于512时，扩容单位为一倍
        //当int左移动时，最后i = 0
        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()]; // size = 53 sizeTable.get(52) = 1073741824
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private final int minCapacity;
        private final int maxCapacity;
        private int index;
        // //预计下一次分配buffer的容量，一开始为2048
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initialIndex, int minCapacity, int maxCapacity) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = initialIndex;
            nextReceiveBufferSize = max(SIZE_TABLE[index], minCapacity);
            this.minCapacity = minCapacity;
            this.maxCapacity = maxCapacity;
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        private void record(int actualReadBytes) {
            //如果实际读取的数据比缩容一个步长以后的内存还小
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                //并且在上一次已经标记为缩容
                if (decreaseNow) {
                    //index不能小于minIndex
                    index = max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = max(SIZE_TABLE[index], minCapacity);
                    decreaseNow = false;
                } else {
                    //下次OP_READ事件继续满足缩容条件的时候，开始真正的进行缩容
                    //对于缩容，比较谨慎
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                //满足一次扩容条件就进行扩容，并且扩容步长为4， 扩容比较奔放
                //Index不能大于maxIndex
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = min(SIZE_TABLE[index], maxCapacity);
                decreaseNow = false;
            }
            //minIndex 和maxIndex 都在类初始化的时候计算
        }

        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initialIndex;
    private final int minCapacity;
    private final int maxCapacity;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        int initialIndex = getSizeTableIndex(initial);
        if (SIZE_TABLE[initialIndex] > initial) {
            this.initialIndex = initialIndex - 1;
        } else {
            this.initialIndex = initialIndex;
        }
        this.minCapacity = minimum;
        this.maxCapacity = maximum;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initialIndex, minCapacity, maxCapacity);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
