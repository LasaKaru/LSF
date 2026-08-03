-- ===========================================================================
-- Seed: the Colombo Fort <-> Badulla up-country line.
--
-- Idempotent (ON CONFLICT DO NOTHING) so restarting a container never
-- duplicates data. Fixed UUIDs make the seed reproducible across environments.
--
-- !! ILLUSTRATIVE DATA !!
-- Distances, timings and the consist are representative and internally
-- consistent, chosen to demonstrate the model. They must be validated against
-- official Sri Lanka Railways timetable and tariff data before any real
-- deployment. See docs/01 A9/A10.
-- ===========================================================================

-- --- Stations (25) ---------------------------------------------------------
INSERT INTO station (code, name_en, name_si, name_ta) VALUES
    ('CMB', 'Colombo Fort',  'කොළඹ කොටුව',        'கொழும்பு கோட்டை'),
    ('RGM', 'Ragama',        'රාගම',              'ராகமை'),
    ('GMP', 'Gampaha',       'ගම්පහ',             'கம்பஹா'),
    ('VYG', 'Veyangoda',     'වේයන්ගොඩ',          'வேயங்கொடை'),
    ('PGW', 'Polgahawela',   'පොල්ගහවෙල',         'பொல்கஹவெல'),
    ('RBK', 'Rambukkana',    'රඹුක්කන',           'ரம்புக்கன'),
    ('KDG', 'Kadugannawa',   'කඩුගන්නාව',         'கடுகண்ணாவ'),
    ('PDN', 'Peradeniya Jn', 'පේරාදෙණිය මංසන්ධිය', 'பேராதெனிய சந்தி'),
    ('KDY', 'Kandy',         'මහනුවර',            'கண்டி'),
    ('GPL', 'Gampola',       'ගම්පොල',            'கம்பளை'),
    ('NWP', 'Nawalapitiya',  'නාවලපිටිය',         'நாவலப்பிட்டிய'),
    ('HAT', 'Hatton',        'හැටන්',             'ஹட்டன்'),
    ('KTG', 'Kotagala',      'කොටගල',             'கொட்டகலை'),
    ('TWL', 'Talawakele',    'තලවාකැලේ',          'தலவாக்கலை'),
    ('NAN', 'Nanu Oya',      'නානුඔය',            'நானு ஓயா'),
    ('AMB', 'Ambewela',      'අඹේවෙල',            'அம்பேவெல'),
    ('PTP', 'Pattipola',     'පත්තිපොල',          'பத்திப்பொல'),
    ('OHY', 'Ohiya',         'ඕහිය',              'ஓஹிய'),
    ('IDG', 'Idalgashinna',  'ඉදල්ගස්හින්න',      'இடல்கஸ்ஹின்ன'),
    ('HPT', 'Haputale',      'හපුතලේ',            'ஹபுத்தளை'),
    ('DTW', 'Diyatalawa',    'දියතලාව',           'தியதலாவ'),
    ('BWL', 'Bandarawela',   'බණ්ඩාරවෙල',         'பண்டாரவளை'),
    ('ELA', 'Ella',          'ඇල්ල',              'எல்ல'),
    ('DMD', 'Demodara',      'දෙමෝදර',            'தெமோதரை'),
    ('BDL', 'Badulla',       'බදුල්ල',            'பதுளை')
ON CONFLICT (code) DO NOTHING;

-- --- Route -----------------------------------------------------------------
INSERT INTO route (id, code, name_en) VALUES
    ('a0000000-0000-4000-8000-000000000001', 'MAIN_UPCOUNTRY',
     'Main Line / Up-country: Colombo Fort - Badulla')
ON CONFLICT (code) DO NOTHING;

-- Cumulative distance from Colombo Fort, in the UP direction.
INSERT INTO route_stop (route_id, station_id, stop_order, distance_from_origin_km)
SELECT 'a0000000-0000-4000-8000-000000000001', s.id, v.stop_order, v.km
  FROM (VALUES
        ('CMB',  1,   0.00), ('RGM',  2,  13.00), ('GMP',  3,  29.00),
        ('VYG',  4,  38.00), ('PGW',  5,  74.00), ('RBK',  6,  84.00),
        ('KDG',  7, 110.00), ('PDN',  8, 116.00), ('KDY',  9, 121.00),
        ('GPL', 10, 133.00), ('NWP', 11, 147.00), ('HAT', 12, 178.00),
        ('KTG', 13, 186.00), ('TWL', 14, 194.00), ('NAN', 15, 210.00),
        ('AMB', 16, 222.00), ('PTP', 17, 227.00), ('OHY', 18, 238.00),
        ('IDG', 19, 250.00), ('HPT', 20, 258.00), ('DTW', 21, 265.00),
        ('BWL', 22, 272.00), ('ELA', 23, 281.00), ('DMD', 24, 288.00),
        ('BDL', 25, 292.00)
       ) AS v(code, stop_order, km)
  JOIN station s ON s.code = v.code
ON CONFLICT (route_id, stop_order) DO NOTHING;

-- --- Coach layouts ---------------------------------------------------------
-- The grid drives the seat-map renderer directly. Adding a layout is one row.
INSERT INTO coach_layout (id, code, description, row_count, column_count,
                          aisle_after_column, seat_count, grid) VALUES
    ('b0000000-0000-4000-8000-000000000001', 'SECOND_2x2_60',
     'Second class reserved, 2+2 across, 15 rows', 15, 4, 2, 60,
     '{"rows":15,"columns":4,"aisleAfterColumn":2,"seatPattern":["A","B","C","D"],
       "windowColumns":[1,4],"facing":"FORWARD","blanks":[]}'::jsonb),

    ('b0000000-0000-4000-8000-000000000002', 'FIRST_AC_2x2_48',
     'First class air-conditioned, 2+2 across, 12 rows', 12, 4, 2, 48,
     '{"rows":12,"columns":4,"aisleAfterColumn":2,"seatPattern":["A","B","C","D"],
       "windowColumns":[1,4],"facing":"FORWARD","blanks":[]}'::jsonb),

    ('b0000000-0000-4000-8000-000000000003', 'OBSERVATION_2x1_36',
     'Observation saloon, 2+1 across, 12 rows', 12, 3, 2, 36,
     '{"rows":12,"columns":3,"aisleAfterColumn":2,"seatPattern":["A","B","C"],
       "windowColumns":[1,3],"facing":"FORWARD","blanks":[]}'::jsonb)
ON CONFLICT (code) DO NOTHING;

-- --- Trains ----------------------------------------------------------------
INSERT INTO train (id, code, name_en, name_si, name_ta, route_id) VALUES
    ('c0000000-0000-4000-8000-000000000001', '1005', 'Podi Menike',
     'පොඩි මැණිකේ', 'பொடி மேனிகே', 'a0000000-0000-4000-8000-000000000001'),
    ('c0000000-0000-4000-8000-000000000002', '1015', 'Udarata Menike',
     'උඩරට මැණිකේ', 'உடரட மேனிகே', 'a0000000-0000-4000-8000-000000000001')
ON CONFLICT (code) DO NOTHING;

-- --- Consist: 3 reserved + 5 unreserved, exactly as the brief describes -----
-- Reserved coaches carry a seat layout and are individually bookable.
-- Unreserved coaches carry a headcount capacity and no seat map.
INSERT INTO train_composition
    (train_id, coach_number, coach_type, class_code, layout_id, position_index,
     is_reservable, capacity)
SELECT t.id, v.coach_number, v.coach_type::coach_type_enum, v.class_code::class_enum,
       v.layout_id::uuid, v.position_index, v.is_reservable, v.capacity
  FROM train t
  CROSS JOIN (VALUES
        ('R1', 'RESERVED',    'SECOND',      'b0000000-0000-4000-8000-000000000001', 1, TRUE,  NULL::int),
        ('R2', 'RESERVED',    'FIRST',       'b0000000-0000-4000-8000-000000000002', 2, TRUE,  NULL),
        ('R3', 'OBSERVATION', 'OBSERVATION', 'b0000000-0000-4000-8000-000000000003', 3, TRUE,  NULL),
        ('U1', 'UNRESERVED',  'SECOND',      NULL,                                   4, FALSE, 100),
        ('U2', 'UNRESERVED',  'SECOND',      NULL,                                   5, FALSE, 100),
        ('U3', 'UNRESERVED',  'THIRD',       NULL,                                   6, FALSE, 120),
        ('U4', 'UNRESERVED',  'THIRD',       NULL,                                   7, FALSE, 120),
        ('U5', 'UNRESERVED',  'THIRD',       NULL,                                   8, FALSE, 120)
      ) AS v(coach_number, coach_type, class_code, layout_id, position_index,
             is_reservable, capacity)
 WHERE t.code IN ('1005', '1015')
ON CONFLICT (train_id, coach_number) DO NOTHING;

-- --- Timetable -------------------------------------------------------------
INSERT INTO schedule (train_id, direction, departs_time, avg_speed_kmh, dwell_minutes)
SELECT t.id, v.direction::direction_enum, v.departs_time::time, v.speed, v.dwell
  FROM train t
  JOIN (VALUES
        ('1005', 'UP',   '05:55', 33.00, 2),
        ('1005', 'DOWN', '17:40', 33.00, 2),
        ('1015', 'UP',   '09:45', 35.00, 2),
        ('1015', 'DOWN', '15:20', 35.00, 2)
       ) AS v(train_code, direction, departs_time, speed, dwell)
    ON v.train_code = t.code
ON CONFLICT (train_id, direction) DO NOTHING;
