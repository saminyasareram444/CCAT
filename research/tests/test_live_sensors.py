import importlib.util
import pathlib
import struct
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('simulator_feed', ROOT/'tools/simulator_feed.py')
feed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(feed)


class SimulatorFeedTests(unittest.TestCase):
    def test_variable_padding_and_valid_values(self):
        data = b'RREF,' + struct.pack('<ififif', 0, 3.5, 1, float('nan'), 999, 7)
        self.assertEqual(feed.parse_packet(data, ('127.0.0.1', 49001), ['pitch', 'roll']), {'pitch': 3.5})

    def test_bad_sender_and_truncated_payload(self):
        data = b'RREF\0' + struct.pack('<if', 0, 3.5)
        self.assertEqual(feed.parse_packet(data, ('192.0.2.1', 49001), ['pitch']), {})
        self.assertEqual(feed.parse_packet(data[:-1], ('127.0.0.1', 49001), ['pitch']), {})
        self.assertEqual(feed.parse_packet(b'RREF', ('127.0.0.1', 49001), ['pitch']), {})

    def test_stale_values_are_not_fabricated(self):
        sensor = feed.Feed.__new__(feed.Feed)
        sensor.values = {'fresh': 0, 'stale': 9}
        sensor.times = {'fresh': time.monotonic(), 'stale': time.monotonic()-2}
        sample = sensor.sample()
        self.assertEqual(sample['values'], {'fresh': 0})
        self.assertFalse(sample['physical_independent_sensors'])
        self.assertIn('simulated', sample['source'])


if __name__ == '__main__':
    unittest.main()
