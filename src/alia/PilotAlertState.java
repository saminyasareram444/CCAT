package alia;

/** Escalate immediately; require a stable recovery so threshold noise cannot flash the banner. */
final class PilotAlertState {
    static final long RECOVERY_NANOS=1_500_000_000L;
    private PilotDisplayModel.Alert displayed,recovery;
    private long recoverySince;
    PilotDisplayModel.Alert update(PilotDisplayModel.Alert incoming,long now) {
        if(displayed==null||incoming.tone().ordinal()>=displayed.tone().ordinal()) {
            displayed=incoming;recovery=null;return displayed;
        }
        if(recovery==null||recovery.tone()!=incoming.tone()||!recovery.title().equals(incoming.title())){recovery=incoming;recoverySince=now;}
        if(now-recoverySince>=RECOVERY_NANOS){displayed=incoming;recovery=null;}
        return displayed;
    }
}
