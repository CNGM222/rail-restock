package dev.railrestock.logic;

/** Exact final remainder, completed synchronously with no cursor carried between ticks. */
public final class CursorTransfer {
    private CursorTransfer() { }

    public interface Access {
        void pickup(boolean half);
        int cursorCount();
        boolean canReturn(int count);
        void returnToSource(boolean single);
        int destination();
        int space(int slot);
        void deposit(int slot, boolean single);
    }

    public static int execute(TransferPlanner.Plan plan, Access access) {
        if (plan.kind() != TransferPlanner.Kind.PICKUP_ALL
                && plan.kind() != TransferPlanner.Kind.PICKUP_HALF) {
            throw new IllegalArgumentException("Expected a cursor pickup plan");
        }
        access.pickup(plan.kind() == TransferPlanner.Kind.PICKUP_HALF);
        expect(access, plan.pickupCount());
        int remaining = plan.transferCount();
        int surplus = plan.pickupCount() - remaining;
        // For a nearly full stack, return the few surplus items first, then place
        // the wanted stack with one left click instead of right-clicking most of it.
        if (surplus > 0 && surplus < remaining && access.canReturn(surplus)) {
            for (int i = 0; i < surplus; i++) {
                int before = access.cursorCount();
                access.returnToSource(true);
                expect(access, before - 1);
            }
        }
        while (remaining > 0) {
            int slot = access.destination();
            int before = access.cursorCount();
            int space = slot < 0 ? 0 : access.space(slot);
            if (space <= 0 || before <= 0) throw new IllegalStateException("背包容量或鼠标物品发生变化");
            int wholeClick = Math.min(before, space);
            boolean single = wholeClick > remaining;
            int moved = single ? 1 : wholeClick;
            access.deposit(slot, single);
            expect(access, before - moved);
            remaining -= moved;
        }
        if (access.cursorCount() > 0) {
            if (!access.canReturn(access.cursorCount())) throw new IllegalStateException("余料无法归还源槽位");
            access.returnToSource(false);
        }
        expect(access, 0);
        return plan.transferCount();
    }

    private static void expect(Access access, int count) {
        if (access.cursorCount() != count) throw new IllegalStateException("取料预测发生变化，停止本次操作");
    }
}
