package com.rchen102.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * 用于封装返回值，与 Invocation 对应
 */
public class ObjectWritable implements Writable {
    // 返回值类型
    private Class declaredClass;
    // 返回值对象
    private Object instance;

    public ObjectWritable() {}

    public ObjectWritable(Class declaredClass, Object instance) {
        this.declaredClass = declaredClass;
        this.instance = instance;
    }

    public Object get() {
        return instance;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        ByteArrayOutputStream byteArrOut = new ByteArrayOutputStream();
        ObjectOutputStream objOut = new ObjectOutputStream(byteArrOut);

        objOut.writeObject(declaredClass);
        objOut.writeObject(instance);
        objOut.flush();

        byte[] data = byteArrOut.toByteArray();
        out.writeInt(data.length);
        out.write(data);
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        int length = in.readInt();
        byte[] data = new byte[length];
        in.readFully(data);

        ByteArrayInputStream byteArrIn = new ByteArrayInputStream(data);
        ObjectInputStream objIn = new ObjectInputStream(byteArrIn);

        try {
            declaredClass = (Class) objIn.readObject();
            instance = objIn.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Class not found when deserialize.");
        }
    }

    @Override
    public String toString() {
        return "OW[class=" + declaredClass + ",value=" + instance + "]";
    }
}
