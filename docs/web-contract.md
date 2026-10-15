# Local web architecture

Web is now primary; Android is retained. `web/server` serves browser and API at `http://127.0.0.1:4317`. Vite is middleware in development; production serves `dist`.

## Disk storage

`CLIPPER_DATA_DIR` defaults to `web/.clipper-data`:

- `projects/<uuid>.json`: envelope containing versioned editor project data.
- `media/<uuid>.<extension>` and `<uuid>.json`: original files/probe metadata.
- `jobs/<uuid>.json`: plans, state, durable output lists.
- `exports/<job-uuid>/`: numbered clips and subtitle sidecars.

JSON writes use temporary files plus rename. Uploads stream to temporary files and probe before commit. API paths use validated server identifiers, never arbitrary client paths.

## API

All API calls require the local browser session cookie and matching Host/Origin. No CORS allowance.

| Endpoint | Purpose |
|---|---|
| GET `/api/status` | Storage and active job |
| GET/POST `/api/media` | List/stream upload |
| GET `/api/media/:id` | Metadata |
| GET `/api/media/:id/content` | Ranged media playback |
| GET `/api/projects` | Project summaries |
| GET/PUT/DELETE `/api/projects/:id` | Project persistence |
| GET/POST `/api/jobs` | List/create clipping jobs |
| POST `/api/jobs/:id/pause` or `/resume` | Job lifecycle |
| GET `/api/jobs/:id/files/:name` | Download a recorded output |

FFmpeg jobs run sequentially with argument arrays and `shell:false`. Restarted active/queued jobs require explicit resume. This is not a public multi-user service.

## Platform boundary

Android remains Kotlin/XML. PC uses TypeScript/React to host the reusable editor. Android JSON and editor timeline documents differ; automatic interchange is not implemented. Shared concepts include clip intervals, quality modes, part labels and SRT timing.
