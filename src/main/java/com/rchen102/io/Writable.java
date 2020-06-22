package com.rchen102.io;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public interface Writable {
    /**
     * 对象属性序列化后，写入out
     * @param out 序列化结果的输出流
     * @throws IOException
     */
    void write(DataOutput out) throws IOException;

    /**
     * 从 <code>in</code> 中反序列化属性
     * @param in 反序列化结果的输入流
     * @throws IOException
     */
    void readFields(DataInput in) throws IOException;
}
