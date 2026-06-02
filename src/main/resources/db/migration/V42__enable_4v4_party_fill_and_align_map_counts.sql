-- Enable the production 4v4 ranked party-fill flow and keep map metadata
-- consistent with the mode arrays already exposed to Unity clients.

UPDATE game_modes
SET mode_status = 'AVAILABLE',
    allow_fill = 1,
    matchmaking_enabled = 1
WHERE mode_key = '4v4';

UPDATE game_maps
SET supported_player_counts = JSON_ARRAY(2, 4, 6, 8, 10)
WHERE map_id = 'map_01';

UPDATE game_maps
SET supported_player_counts = JSON_ARRAY(2, 4, 6)
WHERE map_id = 'map_02';
