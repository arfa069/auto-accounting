# Chain of evidence
- The longest app frame is `160444179`: frame duration `77.723958 ms`, CPU time `81.919933 ms`, UI `Choreographer#doFrame` `45.139844 ms`, RenderThread `DrawFrames` `33.006875 ms`, and overrun `67.830506 ms`.
- UI thread `utid=16`, `tid=8213` is Running for `48.728073 ms` across the frame. Its nested work includes `Recomposer:recompose 17.612969 ms`, `Compose:recompose 9.976718 ms`, and `AndroidOwner:measureAndLayout 20.112760 ms`.
- RenderThread `utid=6174`, `tid=9348` runs the `33.006875 ms` `DrawFrames 160444179` slice. Its `Drawing 0.00 0.00 1080.00 2400.00` child is `32.383073 ms`; `flush commands` is `30.695052 ms`.
- Within that draw, `shader_compile` is `29.530416 ms`, `ShaderCache::cache_miss` is `28.645834 ms`, `driver_compile_shader` is `11.462135 ms`, and `driver_link_program` is `14.451406 ms`.
- During the RenderThread draw the thread is Running for `5.052813 ms`, then `D` for `0.040156 ms`, Runnable for `0.025990 ms`, and Running for `29.770468 ms`; it runs on CPU 6 while frequency transitions from `499200` to `729600` and then `1190400` kHz.
- Process counters peak at RSS `235.457031 MiB`, anonymous RSS `97.765625 MiB`, swap `72.441406 MiB`, and GPU memory `63.015625 MiB`. No slice name matching `*GC*` is present in the trace.
- Trace health reports one `power_rail_empty_packet` import error.
