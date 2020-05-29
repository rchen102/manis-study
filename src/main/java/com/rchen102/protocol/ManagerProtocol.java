package com.rchen102.protocol;

public interface ManagerProtocol {

    /**
     * Set the maximum of table number
     * @param tableNum
     * @return true for success, false for failure
     */
    public boolean setMaxTable(int tableNum);
}
