import type { TCanvasSize } from "../project/types";

import { FRAME_PRESETS } from "../../../../../shared/frame-presets.mjs";

export const DEFAULT_CANVAS_PRESETS: TCanvasSize[] = FRAME_PRESETS.map(({width,height})=>({width,height}));

export const DEFAULT_CANVAS_SIZE: TCanvasSize = { width: 1920, height: 1080 };
