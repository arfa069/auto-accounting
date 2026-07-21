# Chain of evidence
- The long RenderThread frame is `159843497`: CPU time `31.133031 ms`, RenderThread `DrawFrames` `27.606198 ms`, and frame overrun `17.950792 ms`.
- RenderThread `utid=5328`, `tid=7976` runs `DrawFrames 159843497` for `27.606198 ms`; its `Drawing 0.00 0.00 1080.00 2400.00` child is `27.160573 ms` and `flush commands` is `25.560261 ms`.
- This draw contains `shader_compile 14.933438 ms` with `ShaderCache::cache_miss 14.685885 ms`, then a second `shader_compile 8.132813 ms` with `ShaderCache::cache_miss 7.998802 ms`.
- The compiler work includes `driver_compile_shader 5.072136 ms` and `driver_link_program 8.771563 ms` for the first miss, plus `driver_compile_shader 1.984479 ms`, `1.164271 ms`, and `driver_link_program 4.513750 ms` for the second miss.
- RenderThread is Running for `39.586197 ms` across the draw on CPU 4; CPU frequency transitions from `844800` to `960000`, `1612800`, and `2035200` kHz. No thread-state wait accounts for the draw duration.
- Trace health reports one `power_rail_empty_packet` import error.
