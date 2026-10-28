"""Reproducible summaries of completed bounded simulator trials, not ML fitting."""
import argparse
import csv
import hashlib
import json
import math
import pathlib


def summarize(folder):
    outcome = json.loads((folder / 'outcome.json').read_text(encoding='utf-8'))
    if outcome['outcome'] != 'landed':
        return None
    rows = list(csv.DictReader((folder / 'flight.csv').open(encoding='utf-8')))
    column = lambda key: [float(row[key]) for row in rows]
    t, clock = column('elapsed_wall_s'), column('sim_clock_s')
    peak = lambda key: max(abs(x) for x in column(key))
    kw = column('battery_kw')
    # Keep the measured current sign; do not silently convert negative to consumption.
    signed_kwh = sum((kw[i]+kw[i-1])/2*(clock[i]-clock[i-1])/3600 for i in range(1, len(rows)))
    ah_difference = max(max(abs(float(r['ahrs_pitch_pilot_deg'])-float(r['ahrs_pitch_copilot_deg'])), abs(float(r['ahrs_roll_pilot_deg'])-float(r['ahrs_roll_copilot_deg']))) for r in rows)
    return {'flight': folder.name, 'outcome': 'landed', 'rows': len(rows),
            'wall_duration_s': t[-1]-t[0], 'simulator_clock_delta_s': clock[-1]-clock[0],
            'max_sample_gap_s': max(t[i]-t[i-1] for i in range(1, len(t))),
            'simulator_clock_reversals': sum(clock[i] < clock[i-1] for i in range(1, len(clock))),
            'peak_height_above_start_m': max(column('height_m')),
            'max_horizontal_drift_m': max(math.hypot(float(r['north_m']), float(r['east_m'])) for r in rows),
            'touchdown_horizontal_offset_m': math.hypot(float(rows[-1]['north_m']), float(rows[-1]['east_m'])),
            'max_abs_pitch_deg': peak('pitch_deg'), 'max_abs_roll_deg': peak('roll_deg'),
            'max_ahrs_pair_difference_deg': ah_difference,
            'radio_altimeter_pair_max_difference_ft': max(abs(float(r['radio_altimeter_pilot_ft'])-float(r['radio_altimeter_copilot_ft'])) for r in rows),
            'battery_start_pct': column('battery_pct')[0], 'battery_end_pct': column('battery_pct')[-1],
            'signed_electrical_energy_kwh': signed_kwh,
            'max_abs_battery_kw': peak('battery_kw'),
            'gps1_fault_codes': sorted(set(column('gps_failure'))),
            'gps2_fault_codes': sorted(set(column('gps2_failure'))),
            'files': {name: hashlib.sha256((folder / name).read_bytes()).hexdigest() for name in ('flight.csv', 'metadata.json', 'outcome.json')}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=pathlib.Path, default=pathlib.Path('.'))
    args = parser.parse_args()
    root = args.root.resolve()
    flights = [summary for p in sorted((root/'research/local/live-trials').iterdir()) if p.is_dir() and (p/'outcome.json').exists() and (summary := summarize(p))]
    replay_files = sorted((root/'research/local/sensor-experiments').glob('*/results.json'))
    report = {'scope': 'two-repeat simulator pipeline pilot, not independent-scenario generalization or safety validation',
              'simulator': 'X-Plane 12.4.3-r2 build 124311',
              'aircraft': 'installed BETA ALIA-250', 'physical_sensor_independence': False,
              'flights': flights,
              'replay': json.loads(replay_files[-1].read_text(encoding='utf-8')) if replay_files else None}
    output = root/'research/local/live-trials/campaign-results.json'
    output.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
