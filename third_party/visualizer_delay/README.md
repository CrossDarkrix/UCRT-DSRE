# Adjustable visualizer delay

The player spectrum analyzer now includes a 0-250 ms delay slider in 10 ms steps. The default is 80 ms. The value is persisted in player_visualizer preferences and adds to the existing playback-queue delay estimate. Changing the value clears only pending visualization snapshots so old timing cannot block the new setting. Audio playback, DSRE DSP, encoding, metadata and cover handling are unchanged.
