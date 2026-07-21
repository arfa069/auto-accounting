# Chain of evidence
- The longest app frame is `160348818`: frame duration `56.738645 ms`, CPU time `56.316804 ms`, UI `Choreographer#doFrame` `22.681719 ms`, RenderThread `DrawFrames` `32.604271 ms`, and overrun `44.102793 ms`.
- UI thread `utid=24`, `tid=1004` is Running for `22.543281 ms` across the frame. Its nested work includes `Recomposer:recompose 5.338333 ms` and `AndroidOwner:measureAndLayout 12.785364 ms`.
- RenderThread `utid=1399`, `tid=2387` runs the `32.604271 ms` `DrawFrames 160348818` slice. Its `Drawing 0.00 0.00 1080.00 2400.00` child is `32.118594 ms`; `flush commands` is `30.551562 ms`.
- Within that draw, `shader_compile` is `29.257240 ms`, `ShaderCache::cache_miss` is `28.580833 ms`, `driver_compile_shader` is `10.919271 ms`, and `driver_link_program` is `14.483541 ms`.
- RenderThread is Running for `35.089531 ms` on CPU 5 at `1190400` kHz during the draw; no thread-state wait or Binder transaction accounts for the long draw.
- Process counters peak at RSS `237.152344 MiB`, anonymous RSS `96.808594 MiB`, swap `72.722656 MiB`, and GPU memory `63.015625 MiB`. No slice name matching `*GC*` is present in the trace.
- Trace health reports one `power_rail_empty_packet` import error.
