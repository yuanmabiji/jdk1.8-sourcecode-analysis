package test.ymbj.demo.juc.thread;

public class JoinDemo {
    public static void main(String[] args) throws Exception {
        Thread joinThread = new Thread( new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 100; i++) {
                        Thread.sleep(2000);
                        System.out.println("I am a join thread..." + i);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, "joinThread");

        System.out.println("before joinThread join...");
        joinThread.start();
        System.out.println("joinThread.getState()----》》》" + joinThread.getState());
        System.out.println("mainThread.getState()----》》》" + Thread.currentThread().getState());
        joinThread.join(100000);
        System.out.println("after joinThread.join();");
        System.out.println("joinThread.getState()----》》》" + joinThread.getState());
        System.out.println("mainThread.getState()----》》》" + Thread.currentThread().getState());
        // 等待joinThread join后主线程继续执行。
        System.out.println("after joinThread join...");

    }
}
