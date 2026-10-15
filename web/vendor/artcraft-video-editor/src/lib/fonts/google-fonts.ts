import type { FontAtlas } from "./types";
import { SYSTEM_FONTS } from "./system-fonts";

const GOOGLE_FONTS_CSS = "https://fonts.googleapis.com/css2";
const FONT_ATLAS_PATH = "/fonts/font-atlas.json";
const FONT_CHUNK_PATH_PREFIX = "/fonts/font-chunk-";

const fullLoaded = new Set<string>();

let cachedAtlas: FontAtlas | null = null;
let atlasFetchPromise: Promise<FontAtlas | null> | null = null;

function encodeGoogleFontsFamily(family: string): string {
  return family.replace(/ /g, "+");
}

export function getCachedFontAtlas(): FontAtlas | null {
  return cachedAtlas;
}

export function clearFontAtlasCache(): void {
  cachedAtlas = null;
  atlasFetchPromise = null;
  fullLoaded.clear();
}

export function loadFontAtlas(): Promise<FontAtlas | null> {
  return Promise.resolve(null); // Clipper uses installed system fonts only.

}

function preloadChunkImages({ atlas }: { atlas: FontAtlas }): void {
  const maxChunk = Math.max(
    ...Object.values(atlas.fonts).map((entry) => entry.ch),
  );
  for (let i = 0; i <= maxChunk; i++) {
    // hint browser to preload chunk images without blocking
    const img = new Image();
    img.src = `${FONT_CHUNK_PATH_PREFIX}${i}.avif`;
  }
}

export async function loadFullFont({
  family,
  weights = [400, 700],
}: {
  family: string;
  weights?: number[];
}): Promise<void> {
  // No external font requests in the local Clipper host.
  fullLoaded.add(family);

}

export async function loadFonts({
  families,
}: {
  families: string[];
}): Promise<void> {
  const googleFonts = families.filter((family) => !SYSTEM_FONTS.has(family));
  await Promise.all(googleFonts.map((family) => loadFullFont({ family })));
}
