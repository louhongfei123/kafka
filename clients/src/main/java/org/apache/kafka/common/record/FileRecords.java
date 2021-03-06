/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.record;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.network.TransportLayer;
import org.apache.kafka.common.record.FileLogInputStream.FileChannelRecordBatch;
import org.apache.kafka.common.utils.AbstractIterator;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link Records} implementation backed by a file. An optional start and end position can be applied to this
 * instance to enable slicing a range of the log records.
 */
public class FileRecords extends AbstractRecords implements Closeable {
    private final boolean isSlice;
    private final int start;
    private final int end;

    private final Iterable<FileLogInputStream.FileChannelRecordBatch> batches;

    // mutable state
    private final AtomicInteger size;
    private final FileChannel channel;
    private volatile File file;

    /**
     * The {@code FileRecords.open} methods should be used instead of this constructor whenever possible.
     * The constructor is visible for tests.
     */
    public FileRecords(File file,
                       FileChannel channel,
                       int start,
                       int end,
                       boolean isSlice) throws IOException {
        this.file = file;
        this.channel = channel;
        this.start = start;
        this.end = end;
        this.isSlice = isSlice;
        this.size = new AtomicInteger();

        if (isSlice) {
            // don't check the file size if this is just a slice view
            // 如果为切片视图, 那么不检查文件大小
            size.set(end - start);
        } else {
            int limit = Math.min((int) channel.size(), end);
            size.set(limit - start);

            // if this is not a slice, update the file pointer to the end of the file
            // set the file position to the last byte in the file
            // 如果非切片视图, 那么更新文件的结束指针, 设置文件位移指向当前的文件结尾
            channel.position(limit);
        }

        batches = batchesFrom(start);
    }

    @Override
    public int sizeInBytes() {
        return size.get();
    }

    /**
     * Get the underlying file.
     * @return The file
     */
    public File file() {
        return file;
    }

    /**
     * Get the underlying file channel.
     * @return The file channel
     */
    public FileChannel channel() {
        return channel;
    }

    /**
     * Read log batches into the given buffer until there are no bytes remaining in the buffer or the end of the file
     * is reached.
     *
     * @param buffer The buffer to write the batches to
     * @param position Position in the buffer to read from
     * @throws IOException If an I/O error occurs, see {@link FileChannel#read(ByteBuffer, long)} for details on the
     * possible exceptions
     *
     * 读取日志batch到指定缓冲区, 直到缓冲区没有存放空间或者到达文件尾
     */
    public void readInto(ByteBuffer buffer, int position) throws IOException {
        Utils.readFully(channel, buffer, position + this.start);
        buffer.flip();
    }

    /**
     * Return a slice of records from this instance, which is a view into this set starting from the given position
     * and with the given size limit.
     *
     * If the size is beyond the end of the file, the end will be based on the size of the file at the time of the read.
     *
     * If this message set is already sliced, the position will be taken relative to that slicing.
     *
     * @param position The start position to begin the read from
     * @param size The number of bytes after the start position to include
     * @return A sliced wrapper on this message set limited based on the given position and size
     *
     * 返回从指定位置开始且为指定大小的记录视图.
     * 如果指定的大小超出文件范围, 那么使用读取时此文件的大小作为结束位置;
     * 如果当前的消息集已经为一个切片视图, 那么参数position会视为相对于此切片的起始位置
     */
    public FileRecords slice(int position, int size) throws IOException {
        if (position < 0)
            throw new IllegalArgumentException("Invalid position: " + position + " in read from " + file);
        if (size < 0)
            throw new IllegalArgumentException("Invalid size: " + size + " in read from " + file);

        int end = this.start + position + size;
        // handle integer overflow or if end is beyond the end of the file
        if (end < 0 || end >= start + sizeInBytes())
            end = start + sizeInBytes();
        return new FileRecords(file, channel, this.start + position, end, true);
    }

    /**
     * Append log batches to the buffer
     * @param records The records to append
     * @return the number of bytes written to the underlying file
     *
     * 在缓冲区中新增日志记录
     * 参数 records: 追加的记录
     * 返回写入的字节数
     */
    public int append(MemoryRecords records) throws IOException {
        int written = records.writeFullyTo(channel);
        size.getAndAdd(written);
        return written;
    }

    /**
     * Commit all written data to the physical disk
     */
    public void flush() throws IOException {
        channel.force(true);
    }

    /**
     * Close this record set
     */
    public void close() throws IOException {
        flush();
        trim();
        channel.close();
    }

    /**
     * Close file handlers used by the FileChannel but don't write to disk. This is used when the disk may have failed
     */
    public void closeHandlers() throws IOException {
        channel.close();
    }

    /**
     * Delete this message set from the filesystem
     * @throws IOException if deletion fails due to an I/O error
     * @return  {@code true} if the file was deleted by this method; {@code false} if the file could not be deleted
     *          because it did not exist
     * 在文件系统中删除此消息集
     * 抛出 IOException: 如果由于I/O异常导致删除失败
     * 返回 : 如果删除文件成功则返回true, 如果文件不存在则返回false
     */
    public boolean deleteIfExists() throws IOException {
        Utils.closeQuietly(channel, "FileChannel");
        return Files.deleteIfExists(file.toPath());
    }

    /**
     * Trim file when close or roll to next file
     */
    public void trim() throws IOException {
        truncateTo(sizeInBytes());
    }

    /**
     * Update the file reference (to be used with caution since this does not reopen the file channel)
     * @param file The new file to use
     */
    public void setFile(File file) {
        this.file = file;
    }

    /**
     * Rename the file that backs this message set
     * @throws IOException if rename fails.
     */
    public void renameTo(File f) throws IOException {
        try {
            Utils.atomicMoveWithFallback(file.toPath(), f.toPath());
        } finally {
            this.file = f;
        }
    }

    /**
     * Truncate this file message set to the given size in bytes. Note that this API does no checking that the
     * given size falls on a valid message boundary.
     * In some versions of the JDK truncating to the same size as the file message set will cause an
     * update of the files mtime, so truncate is only performed if the targetSize is smaller than the
     * size of the underlying FileChannel.
     * It is expected that no other threads will do writes to the log when this function is called.
     * @param targetSize The size to truncate to. Must be between 0 and sizeInBytes.
     * @return The number of bytes truncated off
     */
    public int truncateTo(int targetSize) throws IOException {
        int originalSize = sizeInBytes();
        if (targetSize > originalSize || targetSize < 0)
            throw new KafkaException("Attempt to truncate log segment " + file + " to " + targetSize + " bytes failed, " +
                    " size of this log segment is " + originalSize + " bytes.");
        if (targetSize < (int) channel.size()) {
            channel.truncate(targetSize);
            size.set(targetSize);
        }
        return originalSize - targetSize;
    }

    @Override
    public ConvertedRecords<? extends Records> downConvert(byte toMagic, long firstOffset, Time time) {
        ConvertedRecords<MemoryRecords> convertedRecords = RecordsUtil.downConvert(batches, toMagic, firstOffset, time);
        if (convertedRecords.recordConversionStats().numRecordsConverted() == 0) {
            // This indicates that the message is too large, which means that the buffer is not large
            // enough to hold a full record batch. We just return all the bytes in this instance.
            // Even though the record batch does not have the right format version, we expect old clients
            // to raise an error to the user after reading the record batch size and seeing that there
            // are not enough available bytes in the response to read it fully. Note that this is
            // only possible prior to KIP-74, after which the broker was changed to always return at least
            // one full record batch, even if it requires exceeding the max fetch size requested by the client.
            return new ConvertedRecords<>(this, RecordConversionStats.EMPTY);
        } else {
            return convertedRecords;
        }
    }

    @Override
    public long writeTo(GatheringByteChannel destChannel, long offset, int length) throws IOException {
        long newSize = Math.min(channel.size(), end) - start;
        int oldSize = sizeInBytes();
        if (newSize < oldSize)
            throw new KafkaException(String.format(
                    "Size of FileRecords %s has been truncated during write: old size %d, new size %d",
                    file.getAbsolutePath(), oldSize, newSize));

        long position = start + offset;
        int count = Math.min(length, oldSize);
        final long bytesTransferred;
        if (destChannel instanceof TransportLayer) {
            TransportLayer tl = (TransportLayer) destChannel;
            bytesTransferred = tl.transferFrom(channel, position, count);
        } else {
            bytesTransferred = channel.transferTo(position, count, destChannel);
        }
        return bytesTransferred;
    }

    /**
     * Search forward for the file position of the last offset that is greater than or equal to the target offset
     * and return its physical position and the size of the message (including log overhead) at the returned offset. If
     * no such offsets are found, return null.
     *
     * @param targetOffset The offset to search for.
     * @param startingPosition The starting position in the file to begin searching from.
     *
     * 查找第一条位移大于等于参数offset的消息, 返回它在日志文件中的物理偏移以及大小(包括日志存储额外开销). 如果不存在这样的位移,
     * 那么返回null
     */
    public LogOffsetPosition searchForOffsetWithSize(long targetOffset, int startingPosition) {
        for (FileChannelRecordBatch batch : batchesFrom(startingPosition)) {
            long offset = batch.lastOffset();
            if (offset >= targetOffset)
                return new LogOffsetPosition(offset, batch.position(), batch.sizeInBytes());
        }
        return null;
    }

    /**
     * Search forward for the first message that meets the following requirements:
     * - Message's timestamp is greater than or equals to the targetTimestamp.
     * - Message's position in the log file is greater than or equals to the startingPosition.
     * - Message's offset is greater than or equals to the startingOffset.
     *
     * @param targetTimestamp The timestamp to search for.
     * @param startingPosition The starting position to search.
     * @param startingOffset The starting offset to search.
     * @return The timestamp and offset of the message found. Null if no message is found.
     *
     * 一直查询直到发现第一条满足如下条件的消息:
     * - 消息时间戳大于等于目标时间戳;
     * - 消息的文件内物理偏移大于等于目标偏移;
     * - 消息的位移大于等于目标位移
     *
     * 参数 targetTimestamp: 目标时间戳
     * 参数 startingPosition: 目标物理偏移
     * 参数 startingOffset: 目标位移
     */
    public TimestampAndOffset searchForTimestamp(long targetTimestamp, int startingPosition, long startingOffset) {
        //从指定物理偏移开始查询, 直到RecordBatch的最大时间戳比目标时间戳大
        for (RecordBatch batch : batchesFrom(startingPosition)) {
            if (batch.maxTimestamp() >= targetTimestamp) {
                // We found a message
                // 从RecordBatch中查询第一条满足条件的消息, 返回其时间戳和位移
                for (Record record : batch) {
                    long timestamp = record.timestamp();
                    if (timestamp >= targetTimestamp && record.offset() >= startingOffset)
                        return new TimestampAndOffset(timestamp, record.offset());
                }
            }
        }
        return null;
    }

    /**
     * Return the largest timestamp of the messages after a given position in this file message set.
     * @param startingPosition The starting position.
     * @return The largest timestamp of the messages after the given position.
     */
    public TimestampAndOffset largestTimestampAfter(int startingPosition) {
        long maxTimestamp = RecordBatch.NO_TIMESTAMP;
        long offsetOfMaxTimestamp = -1L;

        for (RecordBatch batch : batchesFrom(startingPosition)) {
            long timestamp = batch.maxTimestamp();
            if (timestamp > maxTimestamp) {
                maxTimestamp = timestamp;
                offsetOfMaxTimestamp = batch.lastOffset();
            }
        }
        return new TimestampAndOffset(maxTimestamp, offsetOfMaxTimestamp);
    }

    /**
     * Get an iterator over the record batches in the file. Note that the batches are
     * backed by the open file channel. When the channel is closed (i.e. when this instance
     * is closed), the batches will generally no longer be readable.
     * @return An iterator over the batches
     */
    @Override
    public Iterable<FileChannelRecordBatch> batches() {
        return batches;
    }

    @Override
    public String toString() {
        return "FileRecords(file= " + file +
                ", start=" + start +
                ", end=" + end +
                ")";
    }

    /**
     * Get an iterator over the record batches in the file, starting at a specific position. This is similar to
     * {@link #batches()} except that callers specify a particular position to start reading the batches from. This
     * method must be used with caution: the start position passed in must be a known start of a batch.
     * @param start The position to start record iteration from; must be a known position for start of a batch
     * @return An iterator over batches starting from {@code start}
     *
     * 创建一个从指定位置开始的记录迭代器, 这个和{@link #batches()}方法相同, 除了此方法可以指定一个起始位置. 在使用时, 需要
     * 保证传入的位置为一个batch开始的位置.
     * 参数 start: 迭代器的起始位移; 必须为一个batch的开始位置
     */
    public Iterable<FileChannelRecordBatch> batchesFrom(final int start) {
        return new Iterable<FileChannelRecordBatch>() {
            @Override
            public Iterator<FileChannelRecordBatch> iterator() {
                return batchIterator(start);
            }
        };
    }

    @Override
    public AbstractIterator<FileChannelRecordBatch> batchIterator() {
        return batchIterator(start);
    }

    private AbstractIterator<FileChannelRecordBatch> batchIterator(int start) {
        final int end;
        //如果此FileRecords为一个切片视图, 那么使用结束位置; 否则使用整个字节数大小作为结束位置
        if (isSlice)
            end = this.end;
        else
            end = this.sizeInBytes();

        //获取迭代器所对应的消息流
        FileLogInputStream inputStream = new FileLogInputStream(this, start, end);

        //返回迭代器
        return new RecordBatchIterator<>(inputStream);
    }

    public static FileRecords open(File file,
                                   boolean mutable,
                                   boolean fileAlreadyExists,
                                   int initFileSize,
                                   boolean preallocate) throws IOException {
        FileChannel channel = openChannel(file, mutable, fileAlreadyExists, initFileSize, preallocate);
        int end = (!fileAlreadyExists && preallocate) ? 0 : Integer.MAX_VALUE;
        return new FileRecords(file, channel, 0, end, false);
    }

    public static FileRecords open(File file,
                                   boolean fileAlreadyExists,
                                   int initFileSize,
                                   boolean preallocate) throws IOException {
        return open(file, true, fileAlreadyExists, initFileSize, preallocate);
    }

    public static FileRecords open(File file, boolean mutable) throws IOException {
        return open(file, mutable, false, 0, false);
    }

    public static FileRecords open(File file) throws IOException {
        return open(file, true);
    }

    /**
     * Open a channel for the given file
     * For windows NTFS and some old LINUX file system, set preallocate to true and initFileSize
     * with one value (for example 512 * 1025 *1024 ) can improve the kafka produce performance.
     * @param file File path
     * @param mutable mutable
     * @param fileAlreadyExists File already exists or not
     * @param initFileSize The size used for pre allocate file, for example 512 * 1025 *1024
     * @param preallocate Pre-allocate file or not, gotten from configuration.
     */
    private static FileChannel openChannel(File file,
                                           boolean mutable,
                                           boolean fileAlreadyExists,
                                           int initFileSize,
                                           boolean preallocate) throws IOException {
        if (mutable) {
            if (fileAlreadyExists) {
                return new RandomAccessFile(file, "rw").getChannel();
            } else {
                if (preallocate) {
                    RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
                    randomAccessFile.setLength(initFileSize);
                    return randomAccessFile.getChannel();
                } else {
                    return new RandomAccessFile(file, "rw").getChannel();
                }
            }
        } else {
            return new FileInputStream(file).getChannel();
        }
    }

    public static class LogOffsetPosition {
        public final long offset;
        public final int position;
        public final int size;

        public LogOffsetPosition(long offset, int position, int size) {
            this.offset = offset;
            this.position = position;
            this.size = size;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            LogOffsetPosition that = (LogOffsetPosition) o;

            return offset == that.offset &&
                    position == that.position &&
                    size == that.size;

        }

        @Override
        public int hashCode() {
            int result = (int) (offset ^ (offset >>> 32));
            result = 31 * result + position;
            result = 31 * result + size;
            return result;
        }

        @Override
        public String toString() {
            return "LogOffsetPosition(" +
                    "offset=" + offset +
                    ", position=" + position +
                    ", size=" + size +
                    ')';
        }
    }

    public static class TimestampAndOffset {
        public final long timestamp;
        public final long offset;

        public TimestampAndOffset(long timestamp, long offset) {
            this.timestamp = timestamp;
            this.offset = offset;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TimestampAndOffset that = (TimestampAndOffset) o;

            if (timestamp != that.timestamp) return false;
            return offset == that.offset;
        }

        @Override
        public int hashCode() {
            int result = (int) (timestamp ^ (timestamp >>> 32));
            result = 31 * result + (int) (offset ^ (offset >>> 32));
            return result;
        }

        @Override
        public String toString() {
            return "TimestampAndOffset(" +
                    "timestamp=" + timestamp +
                    ", offset=" + offset +
                    ')';
        }
    }
}
