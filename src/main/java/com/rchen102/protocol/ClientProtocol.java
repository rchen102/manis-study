package com.rchen102.protocol;

import java.io.IOException;

public interface ClientProtocol {

    /**
     * Get meta information for target table
     * @param dbName database name
     * @param tbName table name
     * @return number of records existing in the table
     * @throws IOException
     */
    public int getTableCount(String dbName, String tbName) throws IOException;
}
