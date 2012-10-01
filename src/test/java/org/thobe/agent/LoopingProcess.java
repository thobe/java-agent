package org.thobe.agent;

class LoopingProcess
{
    public static void main( String[] args ) throws Exception
    {
        System.out.println( "STARTING" );
        for (; ; )
        {
            Thread.sleep( 1000 );
        }
    }
}
