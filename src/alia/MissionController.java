package alia;

import java.util.List;

interface MissionController {
    HoverMission.Phase phase();
    List<HoverMission.Point> route();
    void start(double time);
    void stop();
    HoverMission.Output update(HoverMission.State state,double time);
    default double radiusLimit(){return 650;}
    default double maximumHeight(){return 85;}
    default double minimumHeight(){return -5;}
    default String destination(){return "Hover test / return to start";}
}
