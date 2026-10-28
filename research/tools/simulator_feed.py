"""Read-only X-Plane UDP instrument recorder. No DREF/CMND/flight-control path.

Sources are simulator truth or simulated instruments, never physical independent
GPS/IMU/LiDAR. RREF coordinates use float32 on the wire; RPOS keeps doubles.
"""
import argparse
import datetime as dt
import json
import math
import pathlib
import socket
import struct
import time

REFS = {
    'paused': 'sim/time/paused',
    'on_ground': 'sim/flightmodel/failures/onground_any',
    'sim_speed': 'sim/time/sim_speed',
    'sim_speed_actual': 'sim/time/sim_speed_actual',
    'sim_clock_s': 'sim/time/total_running_time_sec',
    'flight_clock_s': 'sim/time/total_flight_time_sec',
    'num_engines': 'sim/aircraft/engine/acf_num_engines',
    'ias_kt': 'sim/flightmodel/position/indicated_airspeed',
    'ground_speed_mps': 'sim/flightmodel/position/groundspeed',
    'mass_kg': 'sim/flightmodel/weight/m_total',
    'wind_mps': 'sim/weather/aircraft/wind_now_speed_msc',
    'wind_direction_deg': 'sim/weather/aircraft/wind_now_direction_degt',
    'rain_ratio': 'sim/weather/aircraft/precipitation_on_aircraft_ratio',
    'collective': 'beta/alia_250/collective_ratio',
    'ahrs_pitch_pilot_deg': 'sim/cockpit2/gauges/indicators/pitch_AHARS_deg_pilot',
    'ahrs_pitch_copilot_deg': 'sim/cockpit2/gauges/indicators/pitch_AHARS_deg_copilot',
    'ahrs_roll_pilot_deg': 'sim/cockpit2/gauges/indicators/roll_AHARS_deg_pilot',
    'ahrs_roll_copilot_deg': 'sim/cockpit2/gauges/indicators/roll_AHARS_deg_copilot',
    'ahrs_heading_pilot_magnetic_deg': 'sim/cockpit2/gauges/indicators/heading_AHARS_deg_mag_pilot',
    'ahrs_heading_copilot_magnetic_deg': 'sim/cockpit2/gauges/indicators/heading_AHARS_deg_mag_copilot',
    'radio_altimeter_pilot_ft': 'sim/cockpit2/gauges/indicators/radio_altimeter_height_ft_pilot',
    'radio_altimeter_copilot_ft': 'sim/cockpit2/gauges/indicators/radio_altimeter_height_ft_copilot',
    'gps_failure': 'sim/operation/failures/rel_gps',
    'gps2_failure': 'sim/operation/failures/rel_gps2',
    'gps_course_deviation_dots': 'sim/cockpit2/radios/indicators/gps_hdef_dots_pilot',
    'gps2_course_deviation_dots': 'sim/cockpit2/radios/indicators/gps2_hdef_dots_pilot',
    'gps_has_dme': 'sim/cockpit2/radios/indicators/gps_has_dme',
    'gps_dme_nm': 'sim/cockpit2/radios/indicators/gps_dme_distance_nm',
    'truth_p_dps': 'sim/flightmodel/position/P',
    'truth_q_dps': 'sim/flightmodel/position/Q',
    'truth_r_dps': 'sim/flightmodel/position/R',
    'truth_accel_east_mps2': 'sim/flightmodel/position/local_ax',
    'truth_accel_up_mps2': 'sim/flightmodel/position/local_ay',
    'truth_accel_south_mps2': 'sim/flightmodel/position/local_az',
    'battery_capacity_wh': 'sim/aircraft/electrical/battery_watt_hr_max',
}
for i in range(5):
    REFS[f'throttle_{i}'] = f'sim/cockpit2/engine/actuators/throttle_ratio[{i}]'
    REFS[f'battery_on_{i}'] = f'sim/cockpit2/electrical/battery_on[{i}]'
    REFS[f'battery_wh_{i}'] = f'sim/cockpit/electrical/battery_charge_watt_hr[{i}]'
    REFS[f'battery_volts_{i}'] = f'sim/cockpit2/electrical/battery_voltage_actual_volts[{i}]'
    REFS[f'battery_amps_{i}'] = f'sim/cockpit2/electrical/battery_amps[{i}]'
for axis in ('pitch', 'roll', 'heading'):
    REFS[f'override_{axis}'] = f'sim/operation/override/override_joystick_{axis}'
for i in range(4):
    REFS[f'icao_{i}'] = f'sim/aircraft/view/acf_ICAO[{i}]'


def parse_packet(data, peer, names):
    if peer[0] != '127.0.0.1' or data[:4] != b'RREF' or (len(data)-5) % 8:
        return {}
    return {names[index]: value for index, value in struct.iter_unpack('<if', data[5:])
            if 0 <= index < len(names) and math.isfinite(value)}


class Feed:
    def __init__(self):
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind(('127.0.0.1', 0))
        self.socket.settimeout(.05)
        self.values, self.times, self.position = {}, {}, None
        self.names = list(REFS)
        self.subscribe(20)

    def subscribe(self, rate):
        # No RPOS request: X-Plane has a single RPOS recipient. Do not steal it
        # from the controller. This independent read socket uses RREF only.
        refs = dict(REFS)
        refs.update({f'pos_{k}': v for k, v in {
            'latitude_deg': 'sim/flightmodel/position/latitude',
            'longitude_deg': 'sim/flightmodel/position/longitude',
            'elevation_m': 'sim/flightmodel/position/elevation',
            'agl_m': 'sim/flightmodel/position/y_agl',
            'pitch_deg': 'sim/flightmodel/position/theta',
            'roll_deg': 'sim/flightmodel/position/phi',
            'heading_true_deg': 'sim/flightmodel/position/psi',
            'vsouth_mps': 'sim/flightmodel/position/local_vz',
            'veast_mps': 'sim/flightmodel/position/local_vx',
            'vup_mps': 'sim/flightmodel/position/local_vy',
        }.items()})
        self.names = list(refs)
        for index, ref in enumerate(refs.values()):
            data = b'RREF\0' + struct.pack('<ii400s', rate, index, ref.encode())
            self.socket.sendto(data, ('127.0.0.1', 49000))

    def receive(self):
        try:
            data, peer = self.socket.recvfrom(8192)
        except socket.timeout:
            return
        now = time.monotonic()
        for name, value in parse_packet(data, peer, self.names).items():
            self.values[name] = value
            self.times[name] = now

    def sample(self):
        now = time.monotonic()
        return {'wall_utc': dt.datetime.now(dt.timezone.utc).isoformat(),
                'monotonic_s': now, 'source': 'xplane_simulated_instruments_and_truth',
                'physical_independent_sensors': False,
                'values': {k: v for k, v in self.values.items() if now-self.times[k] < 1},
                'age_s': {k: now-t for k, t in self.times.items()}}

    def close(self):
        self.subscribe(0)
        self.socket.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--seconds', type=float, default=4)
    parser.add_argument('--output', type=pathlib.Path)
    args = parser.parse_args()
    if not 0 < args.seconds <= 1200:
        parser.error('seconds must be 0..1200')
    feed = Feed()
    log = None
    try:
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            log = args.output.open('x', encoding='utf-8')
        end, next_row = time.monotonic()+args.seconds, 0
        while time.monotonic() < end:
            feed.receive()
            now = time.monotonic()
            if log and now >= next_row:
                log.write(json.dumps(feed.sample(), allow_nan=False)+'\n')
                log.flush()
                next_row = now+.1
        result = feed.sample()
        result['missing'] = sorted(set(feed.names)-set(result['values']))
        print(json.dumps(result, indent=2, allow_nan=False))
    finally:
        feed.close()
        if log:
            log.close()


if __name__ == '__main__':
    main()
