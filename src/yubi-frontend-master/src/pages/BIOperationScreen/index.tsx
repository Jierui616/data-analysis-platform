import { history } from '@umijs/max';
import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import ReactECharts from 'echarts-for-react';
import * as echarts from 'echarts';
import { Button, ConfigProvider, DatePicker, Segmented, Select, Skeleton, Space, Spin, message } from 'antd';
import type { ThemeConfig } from 'antd/es/config-provider/context';
import dayjs from 'dayjs';
import { getChartByIdUsingGET, listMyChartByPageUsingPOST } from '@/services/yubi/chartController';
import {
  getRestaurantAlertEvents,
  getRestaurantCampaignReview,
  getRestaurantCostLossMock,
  getRestaurantDateRange,
  getRestaurantDishAnalysis,
  getRestaurantForecastMock,
  getRestaurantGeoScope,
  getRestaurantHqOverview,
  getRestaurantStaffEfficiencyMock,
  getRestaurantStoreOverview,
  importRestaurantFromChart,
} from '@/services/yubi/restaurantController';
import './index.less';

type ViewKey = 'hq' | 'store' | 'region';
const BI_OP_VIEW_STORAGE_KEY = 'bi_operation_screen_view';

/** 顶栏 Ant 控件：方案 A 靛蓝雾面 — 与 #2f84ff / #66c8ff 强调色一致 */
const BI_OP_HEADER_THEME: ThemeConfig = {
  components: {
    Segmented: {
      trackBg: 'rgba(6, 26, 62, 0.96)',
      itemColor: 'rgba(210, 238, 255, 0.9)',
      itemHoverColor: '#ffffff',
      itemHoverBg: 'rgba(47, 132, 255, 0.18)',
      itemSelectedBg: 'rgba(47, 132, 255, 0.52)',
      itemSelectedColor: '#ffffff',
      itemActiveBg: 'rgba(47, 132, 255, 0.28)',
    },
    Select: {
      colorBgContainer: 'rgba(6, 26, 62, 0.96)',
      colorBorder: 'rgba(120, 200, 255, 0.22)',
      colorText: '#e8f4ff',
      colorTextPlaceholder: 'rgba(159, 212, 255, 0.48)',
      colorTextQuaternary: 'rgba(159, 212, 255, 0.78)',
      colorTextTertiary: 'rgba(159, 212, 255, 0.72)',
      activeBorderColor: 'rgba(130, 210, 255, 0.58)',
      hoverBorderColor: 'rgba(130, 210, 255, 0.52)',
      controlOutline: 'rgba(47, 132, 255, 0.35)',
    },
    DatePicker: {
      colorBgContainer: 'rgba(6, 26, 62, 0.96)',
      colorBorder: 'rgba(120, 200, 255, 0.22)',
      colorText: '#e8f4ff',
      colorTextPlaceholder: 'rgba(159, 212, 255, 0.48)',
      activeBorderColor: 'rgba(130, 210, 255, 0.58)',
      hoverBorderColor: 'rgba(130, 210, 255, 0.52)',
      colorSplit: 'rgba(120, 200, 255, 0.28)',
      cellActiveWithRangeBg: 'rgba(47, 132, 255, 0.22)',
    },
    Button: {
      defaultBg: 'rgba(6, 26, 62, 0.96)',
      defaultColor: '#a8d8ff',
      defaultBorderColor: 'rgba(120, 200, 255, 0.22)',
      defaultHoverBg: 'rgba(14, 44, 92, 0.98)',
      defaultHoverColor: '#ffffff',
      defaultHoverBorderColor: 'rgba(130, 210, 255, 0.62)',
      colorPrimary: '#2f84ff',
      colorPrimaryHover: '#4a9dff',
      colorPrimaryActive: '#1f6cdb',
    },
  },
};

const THEME = {
  main: '#2f84ff',
  sub: '#66c8ff',
  accent: '#9ad9ff',
  orange: '#ff9a3d',
  yellow: '#ffd166',
  border: 'rgba(120, 200, 255, 0.18)',
  glow: 'rgba(102, 200, 255, 0.2)',
} as const;

const PANZHIHUA_DISTRICTS = ['东区', '西区', '仁和区', '米易县', '盐边县'] as const;
const PANZHIHUA_KEYWORDS = ['攀枝花', ...PANZHIHUA_DISTRICTS] as const;

const formatNumber = (v: any, digits = 0) => {
  const n = Number(v);
  if (!Number.isFinite(n)) return '-';
  return n.toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits });
};

const formatPercent = (v: any, digits = 2) => {
  const n = Number(v);
  if (!Number.isFinite(n)) return '-';
  const pct = n <= 1 ? n * 100 : n;
  return `${pct.toFixed(digits)}%`;
};

const snackRetailizeCopy = (text: string) => {
  let t = String(text || '');
  t = t.replace(/出餐时长/g, '拣货与收银响应时长');
  t = t.replace(/平均出餐/g, '高峰服务响应');
  t = t.replace(/出餐/g, '拣货/收银响应');
  t = t.replace(/翻台率/g, '店效周转');
  t = t.replace(/翻台/g, '客流周转');
  t = t.replace(/食材成本/g, '商品进货成本');
  t = t.replace(/食材/g, '进货商品');
  return t;
};

const normalizeAlertMessage = (rawMsg: string, storeName?: string) => {
  const msg = String(rawMsg || '');
  const sn = String(storeName || '').trim();
  let out = msg;
  if (msg && sn) {
    if (!msg.includes(sn)) {
      out = msg.replace(/^.{1,20}?店/, `${sn}`);
    }
  }
  return snackRetailizeCopy(out);
};

const pickDistrict = (name?: string) => {
  const t = String(name || '')
    .replace(/攀枝花市/g, '')
    .replace(/攀枝花/g, '')
    .replace(/\s+/g, '')
    .trim();
  if (!t) return '';
  const exact = (PANZHIHUA_DISTRICTS as readonly string[]).find((d) => t.includes(d));
  if (exact) return exact;
  if (t.includes('东区')) return '东区';
  if (t.includes('西区')) return '西区';
  if (t.includes('仁和')) return '仁和区';
  if (t.includes('米易')) return '米易县';
  if (t.includes('盐边')) return '盐边县';
  return '';
};

const inferDistrictFromPzhStoreCode = (code?: string) => {
  const c = String(code || '')
    .trim()
    .toUpperCase();
  if (!c.startsWith('PZH-')) return '';
  if (c.startsWith('PZH-E')) return '东区';
  if (c.startsWith('PZH-W')) return '西区';
  if (c.startsWith('PZH-RH')) return '仁和区';
  if (c.startsWith('PZH-MY')) return '米易县';
  if (c.startsWith('PZH-YB')) return '盐边县';
  return '';
};

const inferDistrictFromDemoStoreId = (sid?: string | number) => {
  const n = Number(sid);
  if (!Number.isFinite(n) || n < 1) return '';
  if (n <= 3) return '东区';
  if (n <= 6) return '西区';
  if (n <= 9) return '仁和区';
  if (n <= 12) return '米易县';
  if (n <= 15) return '盐边县';
  return '';
};

const pickDistrictFromStore = (s: any) => {
  const api = String(s?.district || '').trim();
  if (api && (PANZHIHUA_DISTRICTS as readonly string[]).includes(api)) return api;
  return (
    inferDistrictFromPzhStoreCode(s?.storeCode) ||
    inferDistrictFromDemoStoreId(s?.storeId ?? s?.id) ||
    pickDistrict(`${s?.region || ''} ${s?.storeName || ''} ${s?.name || ''}`)
  );
};

const isPanzhihuaText = (text?: string) => {
  const t = String(text || '');
  return (PANZHIHUA_KEYWORDS as readonly string[]).some((k) => t.includes(k));
};


const centroidFromGeometry = (geom: any): [number, number] | null => {
  const type = geom?.type;
  const coords = geom?.coordinates;
  if (!type || !coords) return null;
  const pts: number[][] = [];
  const pushRing = (ring: any) => {
    if (!Array.isArray(ring)) return;
    ring.forEach((pt) => {
      if (Array.isArray(pt) && pt.length >= 2) {
        const x = Number(pt[0]);
        const y = Number(pt[1]);
        if (Number.isFinite(x) && Number.isFinite(y)) pts.push([x, y]);
      }
    });
  };
  if (type === 'Polygon') {
    if (Array.isArray(coords) && coords[0]) pushRing(coords[0]);
  } else if (type === 'MultiPolygon') {
    if (Array.isArray(coords)) {
      coords.forEach((poly) => {
        if (Array.isArray(poly) && poly[0]) pushRing(poly[0]);
      });
    }
  }
  if (pts.length === 0) return null;
  const sum = pts.reduce((acc, p) => [acc[0] + p[0], acc[1] + p[1]] as [number, number], [0, 0]);
  return [sum[0] / pts.length, sum[1] / pts.length];
};

// 基础点在多边形内判断（射线法），用于校验门店经纬度是否落在所属区县内
const pointInRing = (pt: [number, number], ring: any): boolean => {
  if (!Array.isArray(ring) || ring.length < 3) return false;
  const x = pt[0];
  const y = pt[1];
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i, i += 1) {
    const xi = Number(ring[i]?.[0]);
    const yi = Number(ring[i]?.[1]);
    const xj = Number(ring[j]?.[0]);
    const yj = Number(ring[j]?.[1]);
    if (![xi, yi, xj, yj].every(Number.isFinite)) continue;
    const intersect = yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi + 1e-12) + xi;
    if (intersect) inside = !inside;
  }
  return inside;
};

const pointInGeometry = (pt: [number, number], geom: any): boolean => {
  const type = geom?.type;
  const coords = geom?.coordinates;
  if (!type || !coords) return false;
  if (type === 'Polygon') {
    // coords: [ring1, ring2...], 仅使用外环判断即可
    return pointInRing(pt, coords?.[0]);
  }
  if (type === 'MultiPolygon') {
    // coords: [[[ring1...]], [[ring1...]]]
    if (!Array.isArray(coords)) return false;
    return coords.some((poly: any) => pointInRing(pt, poly?.[0]));
  }
  return false;
};

const geometryBounds = (geom: any): [number, number, number, number] | null => {
  const type = geom?.type;
  const coords = geom?.coordinates;
  if (!type || !coords) return null;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  const pushPt = (pt: any) => {
    if (!Array.isArray(pt) || pt.length < 2) return;
    const x = Number(pt[0]);
    const y = Number(pt[1]);
    if (!Number.isFinite(x) || !Number.isFinite(y)) return;
    if (x < minX) minX = x;
    if (y < minY) minY = y;
    if (x > maxX) maxX = x;
    if (y > maxY) maxY = y;
  };
  const walk = (node: any) => {
    if (!Array.isArray(node)) return;
    if (node.length >= 2 && typeof node[0] === 'number' && typeof node[1] === 'number') {
      pushPt(node);
      return;
    }
    node.forEach(walk);
  };
  walk(coords);
  if (![minX, minY, maxX, maxY].every(Number.isFinite)) return null;
  return [minX, minY, maxX, maxY];
};

const seeded01 = (seed: string) => {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i += 1) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  // 0..1
  return ((h >>> 0) % 100000) / 100000;
};

const randomPointInGeometry = (geom: any, seed: string): [number, number] | null => {
  const b = geometryBounds(geom);
  if (!b) return null;
  const [minX, minY, maxX, maxY] = b;
  const dx = maxX - minX;
  const dy = maxY - minY;
  if (!(dx > 0 && dy > 0)) return null;
  // 先围绕几何中心做随机扰动，命中率更高
  const center = centroidFromGeometry(geom);
  if (center) {
    for (let i = 0; i < 120; i += 1) {
      const r1 = seeded01(`${seed}-cx-${i}`) - 0.5;
      const r2 = seeded01(`${seed}-cy-${i}`) - 0.5;
      const x = center[0] + r1 * dx * 0.6;
      const y = center[1] + r2 * dy * 0.6;
      if (x >= minX && x <= maxX && y >= minY && y <= maxY && pointInGeometry([x, y], geom)) return [x, y];
    }
  }
  // rejection sampling，尝试有限次数，保证不会卡死
  for (let i = 0; i < 200; i += 1) {
    const r1 = seeded01(`${seed}-x-${i}`);
    const r2 = seeded01(`${seed}-y-${i}`);
    const x = minX + dx * r1;
    const y = minY + dy * r2;
    if (pointInGeometry([x, y], geom)) return [x, y];
  }
  return null;
};

const MAP_VERSION = '20260430-redesign';

/** 当前筛选区间与库内数据区间是否存在日历交集（闭区间） */
const dateRangesOverlap = (selStart: string, selEnd: string, dataMin: string, dataMax: string) => {
  const a0 = dayjs(selStart).startOf('day');
  const a1 = dayjs(selEnd).startOf('day');
  const b0 = dayjs(dataMin).startOf('day');
  const b1 = dayjs(dataMax).startOf('day');
  return !a1.isBefore(b0, 'day') && !a0.isAfter(b1, 'day');
};

/** CSV 单行拆分（支持双引号包裹字段，与餐饮导入列格式一致） */
const splitCsvRow = (line: string): string[] => {
  const out: string[] = [];
  let cur = '';
  let inQ = false;
  for (let i = 0; i < line.length; i += 1) {
    const c = line[i];
    if (c === '"') {
      inQ = !inQ;
      continue;
    }
    if (c === ',' && !inQ) {
      out.push(cur.trim());
      cur = '';
      continue;
    }
    cur += c;
  }
  out.push(cur.trim());
  return out;
};

const DATE_HEADER_HINT = /^(date|statdate|stat_date|day|日期)$/i;

/** 从图表绑定的 CSV 文本解析日期列最小/最大日（与后端 import 识别的 date/statDate/日期 等列一致） */
const parseDateRangeFromRestaurantChartCsv = (raw: string | undefined | null): { min: string; max: string } | null => {
  if (!raw || !String(raw).trim()) return null;
  const lines = String(raw)
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);
  if (lines.length < 2) return null;
  const headers = splitCsvRow(lines[0]).map((h) => h.replace(/^\ufeff/, '').trim());
  let dateIdx = headers.findIndex((h) => DATE_HEADER_HINT.test(h));
  if (dateIdx < 0) {
    dateIdx = headers.findIndex((h) => /日期|date|stat/i.test(h));
  }
  const pushIso = (raw: string, acc: { min: dayjs.Dayjs | null; max: dayjs.Dayjs | null }) => {
    const m = String(raw || '').match(/(\d{4}-\d{2}-\d{2})/);
    if (!m) return;
    const d = dayjs(m[1], 'YYYY-MM-DD', true);
    if (!d.isValid()) return;
    if (!acc.min || d.isBefore(acc.min, 'day')) acc.min = d;
    if (!acc.max || d.isAfter(acc.max, 'day')) acc.max = d;
  };

  const acc = { min: null as dayjs.Dayjs | null, max: null as dayjs.Dayjs | null };

  if (dateIdx >= 0) {
    for (let i = 1; i < lines.length; i += 1) {
      const cols = splitCsvRow(lines[i]);
      pushIso(cols[dateIdx] || '', acc);
    }
  }
  // 表头识别失败或该列为空时：逐行扫描单元格中的 YYYY-MM-DD（兼容异常导出）
  if (!acc.min || !acc.max) {
    for (let i = 1; i < lines.length; i += 1) {
      const cols = splitCsvRow(lines[i]);
      cols.forEach((c) => pushIso(c, acc));
    }
  }

  if (!acc.min || !acc.max) return null;
  return { min: acc.min.format('YYYY-MM-DD'), max: acc.max.format('YYYY-MM-DD') };
};

const BIOperationScreen: React.FC = () => {
  const rootRef = useRef<HTMLDivElement | null>(null);
  const [viewportHeight, setViewportHeight] = useState<number>();
  const levelClassName = (level: string) => {
    const l = String(level || '').toUpperCase();
    if (l === 'P1') return 'isP1';
    if (l === 'P2') return 'isP2';
    return 'isP3';
  };
  const [view, setView] = useState<ViewKey>(() => {
    const cached = localStorage.getItem(BI_OP_VIEW_STORAGE_KEY);
    if (cached === 'hq' || cached === 'store' || cached === 'region') {
      return cached;
    }
    return 'hq';
  });
  const [loading, setLoading] = useState(false);
  const [mapReady, setMapReady] = useState(false);
  const [mapMeta, setMapMeta] = useState<{ url?: string; featureCount?: number; districts?: string[] }>({});
  const [dateRange, setDateRange] = useState<[string, string]>([
    dayjs().subtract(6, 'day').format('YYYY-MM-DD'),
    dayjs().format('YYYY-MM-DD'),
  ]);
  const [selectedStoreId, setSelectedStoreId] = useState<string>();
  const [selectedRegion, setSelectedRegion] = useState<string>();
  const [myChartOptions, setMyChartOptions] = useState<{ label: string; value: string }[]>([]);
  const [selectedChartId, setSelectedChartId] = useState<string>();
  const [hqData, setHqData] = useState<any>({});
  const [storeData, setStoreData] = useState<any>({});
  const [dishData, setDishData] = useState<any>({});
  const [alertData, setAlertData] = useState<any>({ events: [] });
  const [forecastData, setForecastData] = useState<any>({});
  const [costRiskData, setCostRiskData] = useState<any>({});
  const [staffEfficiencyData, setStaffEfficiencyData] = useState<any>({});
  const [campaignData, setCampaignData] = useState<any>({});
  const [geoScope, setGeoScope] = useState<any>({});
  const [storeRank, setStoreRank] = useState<any[]>([]);
  const [lastUpdatedAt, setLastUpdatedAt] = useState('');
  // 固定2D版本，不再提供调试切换开关

  const mapMainRef = useRef<HTMLDivElement | null>(null);
  const importedChartIdRef = useRef<string>();
  /** 每个图表最多自动对齐一次日期，避免与用户手动选「空区间」反复打架 */
  const autoDateAlignedChartKeyRef = useRef<string | undefined>(undefined);
  /** 切换图表时已将 dateRange 对齐到该图 CSV 时记下图表 id，避免每次加载覆盖用户手调的日期 */
  const chartDateAppliedForIdRef = useRef<string | undefined>(undefined);
  const [mapSize, setMapSize] = useState<{ width: number; height: number }>({ width: 0, height: 0 });
  const normalizeStoreId = (s: any) => {
    const id = s?.storeId ?? s?.id;
    if (id === undefined || id === null || String(id).trim() === '') return '';
    return String(id);
  };
  const metricLabel = (code?: string) => {
    const c = String(code || '').trim();
    if (!c) return '指标异常';
    const dict: Record<string, string> = {
      revenue: '销售额',
      sales: '销售额',
      gmv: '销售额',
      orderCount: '订单量',
      avgTicket: '客单价',
      grossMarginRate: '毛利率',
      tableTurnover: '店效周转',
      cancelRate: '退单率',
      prepTimeAvgMin: '拣货与收银响应时长',
      inventoryRisk: '库存风险',
      stockoutRate: '缺货率',
      expiryRisk: '临期风险',
      wasteRate: '报损率',
      ingredientCostRate: '商品进货成本率',
    };
    return dict[c] || c;
  };
  const asArray = (input: any): any[] => (Array.isArray(input) ? input : []);

  useLayoutEffect(() => {
    const updateAvailableHeight = () => {
      const el = rootRef.current;
      if (!el) return;
      const rect = el.getBoundingClientRect();
      // 在后台壳内运行时，顶部会被导航和浏览器UI占用，使用实时可用高度避免 100vh 溢出
      const next = Math.max(480, Math.floor(window.innerHeight - rect.top - 8));
      setViewportHeight((prev) => (prev === next ? prev : next));
    };
    updateAvailableHeight();
    const ro = new ResizeObserver(() => updateAvailableHeight());
    if (rootRef.current?.parentElement) ro.observe(rootRef.current.parentElement);
    window.addEventListener('resize', updateAvailableHeight);
    return () => {
      ro.disconnect();
      window.removeEventListener('resize', updateAvailableHeight);
    };
  }, []);

  useEffect(() => {
    localStorage.setItem(BI_OP_VIEW_STORAGE_KEY, view);
  }, [view]);

  useLayoutEffect(() => {
    const el = mapMainRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => {
      const rect = el.getBoundingClientRect();
      const next = { width: Math.round(rect.width), height: Math.round(rect.height) };
      setMapSize((prev) => (prev.width === next.width && prev.height === next.height ? prev : next));
    });
    ro.observe(el);
    const rect = el.getBoundingClientRect();
    setMapSize({ width: Math.round(rect.width), height: Math.round(rect.height) });
    return () => ro.disconnect();
  }, []);

  const queryBody = useMemo(
    () => ({
      startDate: dateRange[0],
      endDate: dateRange[1],
      storeId: view === 'store' ? selectedStoreId : undefined,
      region: undefined,
    }),
    [dateRange, view, selectedStoreId],
  );

  /** 不带 storeId：与「图表导入后的全部门店」一致，供地图/区县聚合使用；避免门店视角把地理范围收成 1 家店导致三视角地图与排行脱节 */
  const globalScopeQueryBody = useMemo(
    () => ({
      startDate: dateRange[0],
      endDate: dateRange[1],
    }),
    [dateRange],
  );

  /** 跳转门店画像时携带与大屏一致的统计窗口 */
  const buildStorePortraitPath = useMemo(
    () => (storeId: string, storeName: string) => {
      const params = new URLSearchParams();
      params.set('storeId', String(storeId));
      params.set('storeName', storeName || '');
      params.set('startDate', dateRange[0]);
      params.set('endDate', dateRange[1]);
      return `/risk_portrait/store?${params.toString()}`;
    },
    [dateRange],
  );

  useEffect(() => {
    let cancelled = false;
    const ensureMap = async () => {
      try {
        const urls = [
          `/maps/panzhihua/510400_full.json?v=${MAP_VERSION}`,
          `https://geo.datav.aliyun.com/areas_v3/bound/510400_full.json?v=${MAP_VERSION}`,
        ];
        let ok = false;
        for (const url of urls) {
          try {
            const res = await fetch(url);
            if (!res.ok) continue;
            const json = await res.json();
            if (!json?.features?.length) continue;
            // 兼容 echarts-gl：确保每个 feature 都有可用的 name（否则内部可能对 undefined 调用 indexOf）
            try {
              (json.features || []).forEach((f: any, idx: number) => {
                if (!f) return;
                if (!f.properties) f.properties = {};
                const p = f.properties;
                const candidate = p.name ?? p.fullname ?? p.NAME ?? p.adcode ?? p.id;
                if (typeof candidate !== 'string' || !candidate.trim()) {
                  p.name = `区域${idx + 1}`;
                } else {
                  p.name = String(candidate).trim();
                }
              });
            } catch {
              // ignore sanitize failure
            }
            // 必须是区县级（5个区县），否则会出现“没有边界线/点位全在一个地方”的现象
            const districtSet = new Set<string>();
            (json.features || []).forEach((f: any) => {
              const n = String(f?.properties?.name || '');
              (PANZHIHUA_DISTRICTS as readonly string[]).forEach((d) => {
                if (n.includes(d)) districtSet.add(d);
              });
            });
            if (districtSet.size !== PANZHIHUA_DISTRICTS.length || (json.features || []).length < PANZHIHUA_DISTRICTS.length) {
              continue;
            }
            (echarts as any).registerMap?.('pzhCity', json);
            ok = !!(echarts as any).getMap?.('pzhCity');
            if (ok) {
              if (!cancelled) {
                setMapMeta({
                  url: String(url),
                  featureCount: Number(json?.features?.length || 0),
                  districts: Array.from(districtSet),
                });
              }
              break;
            }
          } catch {
            // ignore
          }
        }
        if (!cancelled) {
          setMapReady(ok);
          if (!ok) setMapMeta({ url: undefined, featureCount: 0, districts: [] });
          if (!ok) message.error('区县边界地图加载失败：未获取到东区/西区/仁和区/米易县/盐边县（将不会显示边界与门店点位）');
        }
      } catch {
        if (!cancelled) {
          setMapReady(false);
          setMapMeta({ url: undefined, featureCount: 0, districts: [] });
          message.error('区县边界地图加载失败：请检查 /public/maps/panzhihua/510400_full.json 是否可访问');
        }
      }
    };
    ensureMap();
    return () => {
      cancelled = true;
    };
  }, []);

  const loadMyCharts = async () => {
    try {
      const res = await listMyChartByPageUsingPOST({
        current: 1,
        pageSize: 20,
        sortField: 'createTime',
        sortOrder: 'descend',
      } as any);
      const records = (res?.data?.records || []) as any[];
      const options = records.map((r: any) => ({
        value: String(r?.id),
        label: `${r?.name || '未命名图表'}（${dayjs(r?.createTime).format('MM-DD HH:mm')}）`,
      }));
      setMyChartOptions(options);
      if (!selectedChartId && options.length > 0) setSelectedChartId(options[0].value);
    } catch (e: any) {
      message.warning(`加载「图表管理与回看」数据失败：${e?.message || String(e)}`);
    }
  };

  const loadData = async () => {
    setLoading(true);
    try {
      if (!selectedChartId) {
        chartDateAppliedForIdRef.current = undefined;
      }
      // 所选图表：时间范围与图表 CSV 内日期列 min~max 一致（仅在切换图表时应用一次）
      // 注意：图表 id 常为雪花 Long，不能用 Number() 再传给接口，否则 JS 精度丢失会取错图、读不到 chartData
      if (selectedChartId && chartDateAppliedForIdRef.current !== selectedChartId) {
        try {
          const chartRes = await getChartByIdUsingGET({
            id: selectedChartId as unknown as API.getChartByIdUsingGETParams['id'],
          });
          const parsed = parseDateRangeFromRestaurantChartCsv(chartRes?.data?.chartData);
          chartDateAppliedForIdRef.current = selectedChartId;
          if (parsed) {
            setDateRange([parsed.min, parsed.max]);
            return;
          }
        } catch {
          chartDateAppliedForIdRef.current = selectedChartId;
        }
      }
      // 仅在图表切换时执行一次导入，避免每次切视角/日期都重导导致数据范围被反复覆盖
      if (selectedChartId && importedChartIdRef.current !== selectedChartId) {
        await importRestaurantFromChart(selectedChartId);
        importedChartIdRef.current = selectedChartId;
        autoDateAlignedChartKeyRef.current = undefined;
      }
      const chartKey = selectedChartId || '_no_chart';
      // 未选图表或图表无可用日期列时：仍用库内可用区间处理「无交集」情形
      try {
        const drRes = await getRestaurantDateRange(globalScopeQueryBody);
        const dr = drRes?.data as { hasData?: boolean; minDate?: string; maxDate?: string } | undefined;
        // 已选图表但 CSV 未解析出日期时，同样用库内区间对齐，避免仍停留在「今天±7天」
        if (dr?.hasData && dr.minDate && dr.maxDate) {
          const overlaps = dateRangesOverlap(dateRange[0], dateRange[1], dr.minDate, dr.maxDate);
          if (
            !overlaps &&
            autoDateAlignedChartKeyRef.current !== chartKey
          ) {
            autoDateAlignedChartKeyRef.current = chartKey;
            const end = dayjs(dr.maxDate);
            let start = end.subtract(6, 'day');
            const dMin = dayjs(dr.minDate);
            if (start.isBefore(dMin, 'day')) start = dMin;
            const next: [string, string] = [start.format('YYYY-MM-DD'), end.format('YYYY-MM-DD')];
            setDateRange(next);
            return;
          }
        }
      } catch {
        // 日期探测失败不阻断大屏加载
      }
      const sliceRequests: Array<() => Promise<any>> = [
        () => getRestaurantHqOverview(queryBody),
        () => getRestaurantStoreOverview(queryBody),
        () => getRestaurantDishAnalysis(queryBody),
        () => getRestaurantAlertEvents(queryBody),
        () => getRestaurantGeoScope(globalScopeQueryBody),
        () => getRestaurantHqOverview(globalScopeQueryBody),
        () => getRestaurantForecastMock(queryBody),
        () => getRestaurantCostLossMock(queryBody),
        () => getRestaurantStaffEfficiencyMock(queryBody),
        () => getRestaurantCampaignReview(queryBody),
      ];
      const settled = await Promise.allSettled(sliceRequests.map((fn) => fn()));
      const safeData = (idx: number) =>
        settled[idx]?.status === 'fulfilled' ? (settled[idx] as PromiseFulfilledResult<any>).value?.data : undefined;
      let failedSlices = 0;
      settled.forEach((r, i) => {
        if (r.status === 'rejected') {
          failedSlices += 1;
          // eslint-disable-next-line no-console
          console.warn(`[BIOperationScreen] 模块 ${i} 请求失败`, (r as PromiseRejectedResult).reason);
        }
      });
      const hqResData = safeData(0);
      const storeResData = safeData(1);
      const dishResData = safeData(2);
      const alertResData = safeData(3);
      const geoResData = safeData(4);
      const scopeResData = safeData(5);
      const forecastResData = safeData(6);
      const costRiskResData = safeData(7);
      const staffEfficiencyResData = safeData(8);
      const campaignResData = safeData(9);
      setHqData(hqResData || {});
      setStoreData(storeResData || {});
      setDishData(dishResData || {});
      setAlertData(alertResData || { events: [] });
      setForecastData(forecastResData || {});
      setCostRiskData(costRiskResData || {});
      setStaffEfficiencyData(staffEfficiencyResData || {});
      setCampaignData(campaignResData || {});
      setGeoScope(geoResData || {});
      const rank = (scopeResData as any)?.storeRank || [];
      setStoreRank(rank);
      const geoRes = { data: geoResData };
      if (failedSlices > 0) {
        message.warning(`部分大屏模块加载失败（${failedSlices} 项），其余区域仍展示可用数据`);
      }
      const geoStores = ((geoRes?.data as any)?.stores || []) as any[];
      const rankWithId = (rank || []).filter((s: any) => s?.storeId != null);
      const geoWithId = geoStores.filter((s: any) => s?.storeId != null);
      const effectiveRank =
        geoWithId.length > 0 && rankWithId.length < geoWithId.length
          ? geoWithId.map((s: any) => ({
              storeId: s.storeId,
              storeName: s.storeName,
              name: s.storeName,
              storeCode: s.storeCode,
              region: s.region,
              district: s.district,
              revenue: Number(s.revenue ?? 0),
            }))
          : rank || [];
      const pzhRank = effectiveRank.filter((s: any) => !!pickDistrictFromStore(s));
      if (!selectedStoreId && pzhRank.length > 0) setSelectedStoreId(String(pzhRank[0].storeId));
      if (!selectedRegion) {
        // 区县视角固定五区县，默认取当前数据首个可识别区县
        const geoRegions = ((geoRes?.data as any)?.regions || []) as any[];
        const firstDistrict =
          pickDistrict(String(geoRegions.find((r: any) => pickDistrict(r?.region))?.region || '')) ||
          pickDistrict(
            String((pzhRank.find((i: any) => i?.region) || rankWithId.find((i: any) => i?.region))?.region || ''),
          ) ||
          String(PANZHIHUA_DISTRICTS[0]);
        setSelectedRegion(firstDistrict);
      }
      setLastUpdatedAt(dayjs().format('YYYY-MM-DD HH:mm:ss'));
    } catch (e: any) {
      message.error(`加载大屏数据失败：${e?.message || String(e)}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadMyCharts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    loadData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, dateRange, selectedStoreId, selectedRegion, selectedChartId]);

  const summary = view === 'store' ? storeData?.kpi || {} : hqData?.summary || {};
  const alertCount = (alertData?.events || []).length;
  const effectiveStoreRank = useMemo(() => {
    const geoStores = ((geoScope?.stores || []) as any[]).filter((s) => s != null && s.storeId != null);
    const rk = storeRank || [];
    const rkWith = rk.filter((s: any) => s?.storeId != null);
    if (geoStores.length === 0) return rk;
    if (rkWith.length >= geoStores.length) return rk;
    return geoStores.map((s: any) => ({
      storeId: s.storeId,
      storeName: s.storeName,
      name: s.storeName,
      storeCode: s.storeCode,
      region: s.region,
      district: s.district,
      revenue: Number(s.revenue ?? 0),
    }));
  }, [geoScope?.stores, storeRank]);

  const pzhStoreRank = useMemo(
    () => (effectiveStoreRank || []).filter((s: any) => !!pickDistrictFromStore(s)),
    [effectiveStoreRank],
  );

  const storeOptions = pzhStoreRank
    .filter((i: any) => i?.storeId)
    .map((i: any) => ({ value: String(i.storeId), label: i.storeName || `门店${i.storeId}` }));
  const storeNameById = useMemo(() => {
    const m: Record<string, string> = {};
    pzhStoreRank.forEach((i: any) => {
      const id = String(i?.storeId || '');
      if (!id) return;
      m[id] = String(i?.storeName || i?.name || `门店${id}`);
    });
    return m;
  }, [pzhStoreRank]);

  useEffect(() => {
    if (view !== 'store') return;
    if (!storeOptions.length) return;
    const valid = storeOptions.some((o) => o.value === selectedStoreId);
    if (!selectedStoreId || !valid) setSelectedStoreId(storeOptions[0].value);
  }, [view, selectedStoreId, storeOptions]);

  const regionOptions = (PANZHIHUA_DISTRICTS as readonly string[]).map((name) => ({ value: name, label: name }));

  const storeCountForKpi = useMemo(() => {
    if (view === 'store') return selectedStoreId ? 1 : 0;
    // 后端 storeCount 在 scope 里，不在 summary 里
    const fromScope = Number(hqData?.scope?.storeCount ?? geoScope?.scope?.storeCount ?? 0);
    if (Number.isFinite(fromScope) && fromScope > 0) return fromScope;
    // 备用：用门店下拉选项数（通常等于可见门店数）
    return storeOptions?.length || 0;
  }, [view, selectedStoreId, hqData, geoScope, storeOptions]);

  const districtCenterMap = useMemo(() => {
    const geoJson = (echarts as any).getMap?.('pzhCity')?.geoJson;
    const m: Record<string, number[]> = {};
    (geoJson?.features || []).forEach((f: any) => {
      const name = String(f?.properties?.name || '');
      const c = f?.properties?.center || f?.properties?.centroid || f?.properties?.cp;
      if (name && Array.isArray(c) && c.length === 2) {
        m[name] = [Number(c[0]), Number(c[1])];
        return;
      }
      const cc = centroidFromGeometry(f?.geometry);
      if (name && cc) m[name] = [Number(cc[0]), Number(cc[1])];
    });
    return m;
  }, [mapReady]);

  const districtFeatureMap = useMemo(() => {
    const geoJson = (echarts as any).getMap?.('pzhCity')?.geoJson;
    const m: Record<string, any> = {};
    (geoJson?.features || []).forEach((f: any) => {
      const name = String(f?.properties?.name || '').replace(/攀枝花市/g, '').replace(/\s+/g, '').trim();
      if (name) m[name] = f;
    });
    return m;
  }, [mapReady]);

  const storeMapPoints = useMemo(() => {
    const candidates: any[] = [
      ...((geoScope?.stores || []) as any[]),
      ...((storeData?.stores || []) as any[]),
      ...((storeData?.storeList || []) as any[]),
    ];
    const uniq = new Map<string, any>();
    const score = (s: any) => {
      let v = 0;
      const name = String(s?.storeName ?? s?.name ?? '').trim();
      const region = String(s?.region ?? s?.district ?? '').trim();
      const revenue = Number(s?.revenue ?? 0);
      const lng = Number(s?.longitude ?? s?.lng ?? s?.lon ?? s?.storeLongitude ?? s?.storeLng ?? s?.x);
      const lat = Number(s?.latitude ?? s?.lat ?? s?.storeLatitude ?? s?.storeLat ?? s?.y);
      if (name) v += 5;
      if (pickDistrictFromStore({ region, storeName: name, name, storeCode: s?.storeCode })) v += 8;
      if (Number.isFinite(revenue) && revenue > 0) v += 6;
      if (Number.isFinite(lng) && Number.isFinite(lat) && Math.abs(lng) > 1 && Math.abs(lat) > 1) v += 4;
      return v;
    };
    candidates.forEach((s: any, idx: number) => {
      const id = normalizeStoreId(s) || String(s?.storeCode ?? `k${idx}`);
      const prev = uniq.get(id);
      if (!prev) {
        uniq.set(id, s);
        return;
      }
      // 不再“先来先用”，改为“信息质量更高优先”，避免被 geoScope 的空字段覆盖
      if (score(s) >= score(prev)) uniq.set(id, s);
    });
    const seededJitter = (seed: string) => {
      let h = 0;
      for (let i = 0; i < seed.length; i += 1) h = (h * 31 + seed.charCodeAt(i)) >>> 0;
      const a = ((h % 1000) / 1000 - 0.5) * 0.035; // lng offset，缩小偏移，保证更稳落在区县内
      const b = ((((h / 7) % 1000) / 1000) - 0.5) * 0.028; // lat offset
      return [a, b];
    };
    const districtAnchor = (d: string): [number, number] | null => {
      if (!d) return null;
      const feature = districtFeatureMap[d];
      const c = feature?.properties?.center || feature?.properties?.centroid || feature?.properties?.cp;
      if (Array.isArray(c) && c.length === 2 && Number.isFinite(Number(c[0])) && Number.isFinite(Number(c[1]))) {
        return [Number(c[0]), Number(c[1])];
      }
      const cc = centroidFromGeometry(feature?.geometry);
      if (cc) return [Number(cc[0]), Number(cc[1])];
      return null;
    };
    return Array.from(uniq.values())
      .map((s: any, idx: number) => {
        const storeId = normalizeStoreId(s);
        const id = storeId || String(s?.storeCode ?? idx);
        const name = String(s?.storeName ?? s?.name ?? `门店${id}`);
        const region = String(s?.region || s?.district || '');
        // 不能只看 region：很多数据 region=“未分区”，但店名里包含“东区/西区/仁和/米易/盐边”
        const inferredDistrict = pickDistrictFromStore({ region, storeName: name, name, storeCode: s?.storeCode });
        const lng = Number(
          s?.longitude ?? s?.lng ?? s?.lon ?? s?.storeLongitude ?? s?.storeLng ?? s?.x,
        );
        const lat = Number(
          s?.latitude ?? s?.lat ?? s?.storeLatitude ?? s?.storeLat ?? s?.y,
        );

        // 1) 如果有经纬度：只有在“落在所属区县面内”才采用，否则按区县面内随机点重生成（不用行政区几何中心作为默认点）
        const hasCoord = Number.isFinite(lng) && Number.isFinite(lat) && Math.abs(lng) > 1 && Math.abs(lat) > 1;
        if (hasCoord) {
          if (inferredDistrict) {
            const feature = districtFeatureMap[inferredDistrict];
            if (feature && pointInGeometry([lng, lat], feature?.geometry)) {
              const [jLng, jLat] = seededJitter(`${id}-${name}`);
              return {
                id,
                storeId: storeId || id,
                name,
                storeCode: s?.storeCode,
                region: inferredDistrict,
                value: [lng + jLng, lat + jLat, Number(s?.revenue || 0)],
                revenue: Number(s?.revenue || 0),
              };
            }
            // 坐标不在所属区县时：按区县锚点重建点位（基于店名锁区县，不使用跨区默认坐标）
            const anchor = districtAnchor(inferredDistrict);
            if (anchor) {
              const [jLng, jLat] = seededJitter(`${id}-${name}-${inferredDistrict}`);
              return {
                id,
                storeId: storeId || id,
                name,
                storeCode: s?.storeCode,
                region: inferredDistrict,
                value: [anchor[0] + jLng, anchor[1] + jLat, Number(s?.revenue || 0)],
                revenue: Number(s?.revenue || 0),
              };
            }
          } else {
            const [jLng, jLat] = seededJitter(`${id}-${name}`);
            return {
              id,
              storeId: storeId || id,
              name,
              storeCode: s?.storeCode,
              region,
              value: [lng + jLng, lat + jLat, Number(s?.revenue || 0)],
              revenue: Number(s?.revenue || 0),
            };
          }
        }

        // 2) 无可用坐标 or 坐标不可信：根据店名锁定区县，并在该区县面内随机取点（不在此处强行指定默认坐标）
        const d = inferredDistrict;
        if (d) {
          const anchor = districtAnchor(d);
          if (anchor) {
            const [jLng, jLat] = seededJitter(`${id}-${name}-${d}`);
            return {
              id,
              storeId: storeId || id,
              name,
              storeCode: s?.storeCode,
              region: d,
              value: [anchor[0] + jLng, anchor[1] + jLat, Number(s?.revenue || 0)],
              revenue: Number(s?.revenue || 0),
            };
          }
        }
        return null;
      })
      .filter(Boolean) as Array<{
        id: string;
        storeId: string;
        name: string;
        storeCode?: string;
        region: string;
        value: number[];
        revenue: number;
      }>;
  }, [geoScope, storeData, districtFeatureMap]);

  const scopedStoreMapPoints = useMemo(() => {
    if (view === 'store' && selectedStoreId) {
      return storeMapPoints.filter((p) => String(p?.storeId || p?.id) === String(selectedStoreId));
    }
    if (view !== 'region') return storeMapPoints;
    if (!selectedRegion) return storeMapPoints;
    return storeMapPoints.filter(
      (p) => pickDistrictFromStore({ region: p?.region, storeName: p?.name, name: p?.name, storeCode: p?.storeCode }) === selectedRegion,
    );
  }, [storeMapPoints, view, selectedRegion, selectedStoreId]);

  const districtRevenue = useMemo(() => {
    const fromApi: Record<string, number> = {};
    const regions = geoScope?.regions || [];
    regions.forEach((r: any) => {
      const d = pickDistrict(r?.region);
      if (d) fromApi[d] = Number(r?.revenue || r?.regionRevenue || 0);
    });

    const fromStores: Record<string, number> = {};
    const addRev = (target: Record<string, number>, d: string, rev: unknown) => {
      if (!d) return;
      target[d] = Number(target[d] || 0) + Number(rev || 0);
    };
    const geoStoresRaw = (geoScope?.stores || []) as any[];
    const inStoreScope = (sid: unknown) =>
      !(view === 'store' && selectedStoreId) || String(sid) === String(selectedStoreId);
    const geoStores =
      view === 'store' && selectedStoreId
        ? geoStoresRaw.filter((s: any) => String(s?.storeId) === String(selectedStoreId))
        : geoStoresRaw;
    if (geoStores.length > 0) {
      geoStores.forEach((s: any) => {
        addRev(fromStores, pickDistrictFromStore(s), s?.revenue);
      });
    }
    let sumStores = Object.values(fromStores).reduce((a, b) => a + b, 0);
    if (!(sumStores > 0)) {
      (storeRank || [])
        .filter((s: any) => inStoreScope(s?.storeId))
        .forEach((s: any) => {
          addRev(fromStores, pickDistrictFromStore(s), s?.revenue);
        });
      sumStores = Object.values(fromStores).reduce((a, b) => a + b, 0);
    }
    if (!(sumStores > 0)) {
      (storeMapPoints || [])
        .filter((p: any) => inStoreScope(p?.storeId ?? p?.id))
        .forEach((p: any) => {
          addRev(fromStores, pickDistrictFromStore({ region: p?.region, storeName: p?.name, name: p?.name, storeCode: p?.storeCode }), p?.revenue);
        });
      sumStores = Object.values(fromStores).reduce((a, b) => a + b, 0);
    }

    const sumApi = Object.values(fromApi).reduce((a, b) => a + Number(b || 0), 0);
    const apiPositiveDistricts = Object.keys(fromApi).filter((k) => Number(fromApi[k]) > 0).length;
    const storePositiveDistricts = Object.keys(fromStores).filter((k) => Number(fromStores[k]) > 0).length;

    const map: Record<string, number> = {};
    const preferStores =
      (view === 'store' && !!selectedStoreId) ||
      (sumStores > 0 &&
        (sumStores >= sumApi - 0.01 || storePositiveDistricts > apiPositiveDistricts || !(sumApi > 0)));
    const source = preferStores ? fromStores : Object.keys(fromApi).length ? fromApi : fromStores;
    Object.assign(map, source);

    (PANZHIHUA_DISTRICTS as readonly string[]).forEach((d) => {
      if (!(d in map)) map[d] = 0;
    });
    return map;
  }, [geoScope, storeMapPoints, storeRank, view, selectedStoreId]);

  const districtSeriesData = useMemo(() => {
    const mapNameByNorm: Record<string, string> = {};
    const geoJson = (echarts as any).getMap?.('pzhCity')?.geoJson;
    (geoJson?.features || []).forEach((f: any) => {
      const name = String(f?.properties?.name || '');
      const norm = String(name).replace(/攀枝花市/g, '').replace(/\s+/g, '').trim();
      if (norm) mapNameByNorm[norm] = name;
    });
    return (PANZHIHUA_DISTRICTS as readonly string[]).map((d) => ({
      name: mapNameByNorm[d] || d,
      value: Number(districtRevenue[d] || 0),
    }));
  }, [districtRevenue, mapReady]);

  const topDishes = useMemo(() => (dishData?.hotDishes || []).slice(0, 6), [dishData]);

  const trendRows = useMemo(() => {
    const src = (view === 'store' ? storeData?.trend : hqData?.trend) || [];
    const rows = Array.isArray(src) ? src : [];
    // 兼容字段：date/statDate/day + revenue/sales
    return rows
      .map((r: any) => ({
        date: String(r?.date || r?.statDate || r?.day || ''),
        revenue: Number(r?.revenue ?? r?.sales ?? r?.gmv ?? 0),
      }))
      .filter((r) => r.date);
  }, [hqData, storeData, view]);

  const leftTrendOption = useMemo(() => {
    const last = trendRows.slice(-8);
    const data = last.map((r) => Number(r.revenue || 0));
    const labels = last.map((r) => {
      const d = String(r.date || '');
      return d.length >= 10 ? d.slice(5, 10) : d;
    });
    return {
      grid: { top: 12, right: 12, left: 44, bottom: 28 },
      xAxis: {
        type: 'category',
        data: labels,
        axisLabel: { color: '#75a8e6', fontSize: 10 },
        axisLine: { lineStyle: { color: 'rgba(99,157,229,.45)' } },
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: '#75a8e6', fontSize: 10 },
        splitLine: { lineStyle: { color: 'rgba(99,157,229,.15)' } },
      },
      series: [
        {
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          data,
          lineStyle: { color: THEME.sub, width: 2 },
          itemStyle: { color: THEME.accent },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(42,206,255,.38)' },
              { offset: 1, color: 'rgba(42,206,255,0)' },
            ]),
          },
        },
      ],
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v: any) => `${formatNumber(v)} 元`,
      },
      backgroundColor: 'transparent',
    };
  }, [trendRows]);

  const dishPieOption = useMemo(() => {
    const data = topDishes.map((d: any) => ({ name: d?.dishName || '未命名', value: Number(d?.revenue || 0) }));
    return {
      backgroundColor: 'transparent',
      tooltip: { trigger: 'item' },
      series: [
        {
          type: 'pie',
          radius: ['55%', '72%'],
          center: ['50%', '52%'],
          label: { color: '#dfeeff', formatter: '{b}' },
          data,
          itemStyle: { borderColor: 'rgba(18, 40, 80, 0.85)', borderWidth: 1 },
        },
      ],
      color: [THEME.sub, THEME.main, THEME.accent, 'rgba(102,200,255,0.55)', 'rgba(47,132,255,0.35)', THEME.yellow],
    };
  }, [topDishes]);

  const regionRoseOption = useMemo(() => {
    const sorted = (PANZHIHUA_DISTRICTS as readonly string[])
      .map((d) => ({ name: d, value: Number(districtRevenue[d] || 0) }))
      .sort((a, b) => b.value - a.value);
    const total = sorted.reduce((sum, it) => sum + Number(it.value || 0), 0);
    return {
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        formatter: (p: any) => `${p?.name || '-'}<br/>销售额：${formatNumber(p?.value)} (${total > 0 ? ((Number(p?.value || 0) / total) * 100).toFixed(1) : '0.0'}%)`,
      },
      legend: {
        bottom: 0,
        left: 'center',
        itemWidth: 14,
        itemHeight: 8,
        itemGap: 12,
        textStyle: { color: '#8fb9ef', fontSize: 10 },
      },
      graphic: [
        {
          type: 'text',
          left: 'center',
          top: '40%',
          style: {
            text: `总量\n${formatNumber(total)}`,
            fill: '#d8f2ff',
            fontSize: 12,
            fontWeight: 600,
            align: 'center',
            textAlign: 'center',
          },
          silent: true,
        },
      ],
      series: [
        {
          name: '区域销售',
          type: 'pie',
          roseType: 'radius',
          radius: ['30%', '66%'],
          center: ['50%', '40%'],
          minAngle: 8,
          avoidLabelOverlap: true,
          minShowLabelAngle: 5,
          labelLayout: { hideOverlap: true, moveOverlap: 'shiftY' },
          label: {
            color: '#cde7ff',
            fontSize: 11,
            formatter: (p: any) => String(p?.name || ''),
          },
          labelLine: {
            length: 10,
            length2: 12,
            smooth: 0.2,
            lineStyle: { color: 'rgba(143,185,239,.7)' },
          },
          itemStyle: {
            borderColor: 'rgba(8, 30, 70, 0.9)',
            borderWidth: 1,
            shadowBlur: 10,
            shadowColor: 'rgba(70, 190, 255, 0.25)',
          },
          data: sorted,
        },
      ],
      color: ['#62d7ff', '#3f97ff', '#5f7cff', '#37c8a9', THEME.accent],
    };
  }, [districtRevenue]);

  const rightHourOption = useMemo(() => {
    const last = trendRows.slice(-8);
    const data = last.map((r) => Number(r.revenue || 0));
    const labels = last.map((r) => {
      const d = String(r.date || '');
      return d.length >= 10 ? d.slice(5, 10) : d;
    });
    return {
      grid: { top: 14, right: 12, left: 36, bottom: 24 },
      xAxis: {
        type: 'category',
        data: labels,
        axisLabel: { color: '#75a8e6', fontSize: 10 },
        axisLine: { lineStyle: { color: 'rgba(99,157,229,.45)' } },
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: '#75a8e6', fontSize: 10 },
        splitLine: { lineStyle: { color: 'rgba(99,157,229,.15)' } },
      },
      series: [
        {
          type: 'bar',
          barWidth: 12,
          data,
          itemStyle: {
            borderRadius: [4, 4, 0, 0],
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: '#2be5ff' },
              { offset: 1, color: '#1979ff' },
            ]),
          },
        },
      ],
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v: any) => `${formatNumber(v)} 元`,
      },
      backgroundColor: 'transparent',
    };
  }, [trendRows]);

  const mapOption = useMemo(() => {
    // 关键：按容器短边计算地图尺寸，避免“按高度放大”导致总部（中列更窄）视觉被挤大
    const shortSide = Math.min(Number(mapSize.width || 0), Number(mapSize.height || 0));
    // 总部/门店：地图几何中心对齐容器中心；区域视角同上
    const sizeRatio = view === 'hq' ? 0.88 : 0.9;
    const tiltLayoutSize = shortSide > 0 ? Math.round(shortSide * sizeRatio) : '88%';
    const tiltLayoutCenter: [string, string] = ['50%', '50%'];
    // 区域：仅平移便于扫边界；总部/门店：可缩放（总部用 scale 避免误拖把整个城市拖出画框）
    const roam: true | 'move' | 'scale' = view === 'region' ? 'move' : view === 'hq' ? 'scale' : true;
    const canScale = view !== 'region';
    return {
      backgroundColor: 'transparent',
      graphic: [],
      tooltip: {
        trigger: 'item',
        formatter: (p: any) => {
          const seriesType = p?.seriesType;
          if (seriesType === 'scatter' || seriesType === 'effectScatter') {
            return `${p?.data?.name || p?.name || '-'}<br/>所属区县：${p?.data?.region || '-'}<br/>销售额：${formatNumber(
              p?.data?.revenue,
            )}`;
          }
          return `行政区：${p?.name || '-'}<br/>销售额：${formatNumber(p?.value)}`;
        },
      },
      // 取消营收热力填充：统一底色
      geo: {
        map: 'pzhCity',
        roam,
        zoom: 1,
        scaleLimit: {
          min: canScale ? 0.75 : 1,
          max: canScale ? 6 : 1,
        },
        layoutCenter: tiltLayoutCenter,
        layoutSize: tiltLayoutSize,
        silent: false,
        // Geo 不参与绘制区县名（由下方 map serie 出面心 label），避免以后误开两套字重叠
        label: { show: false },
        itemStyle: {
          areaColor: 'rgba(18, 72, 138, 0.88)',
          borderColor: 'rgba(120, 200, 255, 0.42)',
          borderWidth: 1.2,
        },
        emphasis: {
          itemStyle: {
            areaColor: 'rgba(36, 110, 188, 0.72)',
            borderColor: 'rgba(180, 230, 255, 0.85)',
            borderWidth: 1.6,
          },
        },
      },
      series: [
        {
          name: '行政边界',
          type: 'map',
          geoIndex: 0,
          data: districtSeriesData.map((d: any) => ({ name: d.name, value: d.value })),
          silent: true,
          zlevel: 8,
          itemStyle: {
            areaColor: 'transparent',
            borderColor: 'rgba(86, 200, 255, 0.92)',
            borderWidth: 2.4,
            shadowBlur: 22,
            shadowColor: 'rgba(70, 180, 255, 0.55)',
            shadowOffsetY: 0,
          },
          emphasis: {
            itemStyle: {
              areaColor: 'rgba(40, 110, 175, 0.28)',
              borderColor: 'rgba(160, 235, 255, 0.95)',
              borderWidth: 3,
              shadowBlur: 28,
              shadowColor: 'rgba(140, 220, 255, 0.75)',
            },
          },
          label: { show: false },
        },
        {
          name: '区县热力',
          type: 'map',
          geoIndex: 0,
          data: districtSeriesData,
          zlevel: 4,
          // 区名由上方“行政边界”系列统一绘制，防止双图层 label 互相覆盖/抖动
          label: { show: false },
          itemStyle: {
            // 常态统一底色，避免多区同时“亮起”（略暗靛蓝，与底图对比更稳）
            areaColor: 'rgba(22, 88, 158, 0.92)',
            borderColor: 'rgba(130, 210, 255, 0.55)',
            borderWidth: 1.4,
            shadowBlur: 10,
            shadowColor: 'rgba(40, 120, 200, 0.22)',
          },
          emphasis: {
            label: { show: false },
            itemStyle: {
              areaColor: 'rgba(48, 130, 210, 0.82)',
              shadowBlur: 22,
              shadowColor: 'rgba(90, 200, 255, 0.45)',
            },
          },
        },
        ...(scopedStoreMapPoints.length === 0
          ? [
              {
                type: 'scatter',
                coordinateSystem: 'geo',
                geoIndex: 0,
                data: [],
                // 仅用于提示门店点位来源不足，不影响图层
                tooltip: { formatter: '暂无可用门店坐标，请检查门店经纬度或区县字段' },
              },
            ]
          : []),
        ...(scopedStoreMapPoints.length > 0
          ? [
              {
                name: '门店标记',
                type: 'effectScatter',
                coordinateSystem: 'geo',
                geoIndex: 0,
                // 三个视角都展示；总部/区域视角不显示文字但保留涟漪以确保“看得见”
                data: view === 'store' ? scopedStoreMapPoints : scopedStoreMapPoints.slice(0, 80),
                symbol: 'circle',
                symbolSize: view === 'store' ? 14 : 13,
                rippleEffect: {
                  scale: view === 'store' ? 2.6 : 2.2,
                  period: 5,
                  brushType: 'stroke',
                },
                label: {
                  show: view === 'store',
                  formatter: (p: any) => p?.data?.name || p?.name || '',
                  position: 'top',
                  color: '#e8f5ff',
                  fontSize: 10,
                  backgroundColor: 'rgba(8,30,70,.6)',
                  padding: [2, 4],
                  borderRadius: 3,
                },
                itemStyle: {
                  color: 'rgba(86, 233, 255, 1)',
                  borderColor: 'rgba(240, 252, 255, 1)',
                  borderWidth: 2,
                  shadowBlur: 22,
                  shadowColor: 'rgba(86, 233, 255, 1)',
                },
                emphasis: {
                  scale: true,
                  itemStyle: {
                    color: 'rgba(120, 245, 255, 1)',
                    shadowBlur: 30,
                    shadowColor: 'rgba(120,245,255,1)',
                  },
                },
                tooltip: {
                  formatter: (p: any) =>
                    `${p?.data?.name || '-'}<br/>所属区县：${p?.data?.region || '-'}<br/>销售额：${formatNumber(
                      p?.data?.revenue,
                    )}`,
                },
                zlevel: 12,
              },
              ...(view === 'store'
                ? [
                    {
                      name: '当前门店',
                      type: 'effectScatter',
                      coordinateSystem: 'geo',
                      geoIndex: 0,
                      data: scopedStoreMapPoints.filter(
                        (p: { id: string; storeId?: string }) =>
                          String(p?.storeId || p?.id) === String(selectedStoreId),
                      ),
                      symbolSize: 18,
                      rippleEffect: { scale: 4, period: 5, brushType: 'stroke' },
                      itemStyle: {
                        color: '#ff7a45',
                        shadowBlur: 12,
                        shadowColor: 'rgba(255,122,69,.55)',
                      },
                      label: {
                        show: true,
                        formatter: (p: any) => `当前：${p?.data?.name || ''}`,
                        position: 'right',
                        color: '#ffd6c2',
                        fontWeight: 600,
                      },
                      zlevel: 9,
                    },
                  ]
                : []),
            ]
          : []),
      ],
    };
  }, [
    districtSeriesData,
    mapSize.height,
    mapSize.width,
    view,
    selectedRegion,
    storeMapPoints,
    scopedStoreMapPoints,
    selectedStoreId,
  ]);

  const hqTopRiskRegion = useMemo(() => {
    const top = (PANZHIHUA_DISTRICTS as readonly string[])
      .map((d) => ({ name: d, revenue: Number(districtRevenue[d] || 0) }))
      .sort((a, b) => b.revenue - a.revenue)[0];
    return top?.name || '-';
  }, [districtRevenue]);

  const hqRevenueDelta = useMemo(() => {
    const rows = trendRows.slice(-2);
    if (rows.length < 2) return 0;
    const prev = Number(rows[0]?.revenue || 0);
    const curr = Number(rows[1]?.revenue || 0);
    if (!(prev > 0)) return 0;
    return ((curr - prev) / prev) * 100;
  }, [trendRows]);

  const hqTargetRate = useMemo(() => {
    const rows = trendRows.slice(-8, -1);
    const avg =
      rows.length > 0
        ? rows.reduce((sum, r) => sum + Number(r?.revenue || 0), 0) / rows.length
        : Number(summary?.revenue || 0);
    const target = avg * 1.05;
    const current = Number(summary?.revenue || 0);
    if (!(target > 0)) return 0;
    return (current / target) * 100;
  }, [trendRows, summary]);

  const hqAlertStats = useMemo(() => {
    const events = (alertData?.events || []) as any[];
    let p1 = 0;
    let p2 = 0;
    events.forEach((e: any) => {
      const lv = String(e?.severity || '').toUpperCase();
      if (lv === 'P1') p1 += 1;
      if (lv === 'P2') p2 += 1;
    });
    return { p1, p2 };
  }, [alertData]);

  const kpiCards = useMemo(() => {
    if (view === 'hq') {
      return [
        {
          label: '销售总额',
          value: `${formatNumber(summary?.revenue)} 元`,
          sub: `较上一时段 ${hqRevenueDelta >= 0 ? '+' : ''}${hqRevenueDelta.toFixed(1)}%`,
        },
        {
          label: '目标达成率',
          value: `${hqTargetRate.toFixed(1)}%`,
          sub: '目标=近7日均值上浮5%',
        },
        {
          label: '毛利率',
          value: formatPercent(summary?.grossMarginRate, 2),
          sub: `客单价 ${formatNumber(summary?.avgTicket || 0, 2)} 元`,
        },
        {
          label: 'P1/P2预警数',
          value: `${hqAlertStats.p1 + hqAlertStats.p2}`,
          sub: `P1 ${hqAlertStats.p1} ｜ P2 ${hqAlertStats.p2}`,
        },
        {
          label: '高风险区域',
          value: hqTopRiskRegion,
          sub: `预警总数 ${alertCount} 条`,
        },
      ];
    }
    return [
      { label: '门店数', value: formatNumber(storeCountForKpi), sub: '-' },
      { label: '在营SKU数', value: formatNumber(summary?.pileCount), sub: '-' },
      { label: '销售总额', value: formatNumber(summary?.revenue), sub: '-' },
      { label: '销售单量', value: formatNumber(summary?.orderCount), sub: '-' },
      { label: '库存风险预警数', value: String(alertCount), sub: '-' },
    ];
  }, [view, summary, alertCount, storeCountForKpi, hqRevenueDelta, hqTargetRate, hqAlertStats, hqTopRiskRegion]);

  const alertRows = useMemo(() => {
    const src = (alertData?.events || []).slice(0, 10);
    return src
      .map((e: any) => ({
        severity: String(e?.severity || 'P2'),
        metric: String(e?.metricCode || '指标异常'),
        msg: normalizeAlertMessage(String(e?.message || ''), storeNameById[String(e?.storeId || '')]),
      }))
      .filter((e: any) => isPanzhihuaText(`${e.metric} ${e.msg}`));
  }, [alertData, storeNameById]);

  const storeDistrictById = useMemo(() => {
    const m: Record<string, string> = {};
    ((geoScope?.stores || []) as any[]).forEach((s: any) => {
      const sid = String(s?.storeId || '');
      if (!sid) return;
      m[sid] = pickDistrictFromStore(s) || '';
    });
    (storeMapPoints || []).forEach((p: any) => {
      const d = pickDistrictFromStore({ region: p?.region, storeName: p?.name, name: p?.name, storeCode: p?.storeCode }) || '';
      const id = String(p?.id || '');
      const sid = String(p?.storeId || '');
      if (id) m[id] = d;
      if (sid) m[sid] = d;
    });
    (storeRank || []).forEach((s: any) => {
      const sid = String(s?.storeId || '');
      if (!sid || m[sid]) return;
      m[sid] = pickDistrictFromStore(s) || '';
    });
    return m;
  }, [geoScope?.stores, storeMapPoints, storeRank]);

  const panzhihuaStoreIdSet = useMemo(() => {
    const set = new Set<string>();
    ((geoScope?.stores || []) as any[]).forEach((s: any) => {
      const id = String(s?.storeId || '');
      if (!id) return;
      if (pickDistrictFromStore(s)) set.add(id);
    });
    (storeMapPoints || []).forEach((p: any) => {
      const d = pickDistrictFromStore({ region: p?.region, storeName: p?.name, name: p?.name, storeCode: p?.storeCode });
      const id = String(p?.id || '');
      const sid = String(p?.storeId || '');
      if (d && id) set.add(id);
      if (d && sid) set.add(sid);
    });
    (storeRank || []).forEach((s: any) => {
      const id = String(s?.storeId || '');
      if (!id) return;
      const d = pickDistrictFromStore(s);
      if (d) set.add(id);
    });
    return set;
  }, [geoScope?.stores, storeMapPoints, storeRank]);

  const visibleStoreIdSet = useMemo(() => {
    // “如实反映当前数据口径”：以当前页面能识别到的门店集合为准（来自 geoScope/storeMapPoints + storeRank）
    // 不再依赖城市关键词猜测；只要 storeId 不在集合内，就不应该出现在三个视角的预警里
    const set = new Set<string>();
    ((geoScope?.stores || []) as any[]).forEach((s: any) => {
      const id = String(s?.storeId || '');
      if (id) set.add(id);
    });
    (storeMapPoints || []).forEach((p: any) => {
      const id = String(p?.storeId || p?.id || '');
      if (id) set.add(id);
    });
    (storeRank || []).forEach((s: any) => {
      const id = String(s?.storeId || '');
      if (id) set.add(id);
    });
    return set;
  }, [geoScope?.stores, storeMapPoints, storeRank]);

  const isPanzhihuaAlertEvent = (e: any) => {
    const storeId = String(e?.storeId || '');
    // 只认 storeId：保证“数据链路如实反映”，不靠文本猜测城市/区县
    if (!storeId) return false;
    return panzhihuaStoreIdSet.has(storeId);
  };

  const scopedAlertEvents = useMemo(() => {
    const events = (alertData?.events || []) as any[];
    const inVisibleScope = (arr: any[]) =>
      arr.filter((e: any) => {
        const sid = String(e?.storeId || '');
        return !!sid && visibleStoreIdSet.has(sid);
      });
    if (view === 'store') {
      if (!selectedStoreId) return inVisibleScope(events).filter((e: any) => isPanzhihuaAlertEvent(e));
      const selectedName = String(storeNameById[String(selectedStoreId)] || '');
      return inVisibleScope(events).filter((e: any) => {
        const sid = String(e?.storeId || '');
        if (sid === String(selectedStoreId)) return true;
        return selectedName ? false : false;
      });
    }
    if (view === 'region') {
      if (!selectedRegion) return inVisibleScope(events).filter((e: any) => isPanzhihuaAlertEvent(e));
      return inVisibleScope(events).filter((e: any) => storeDistrictById[String(e?.storeId || '')] === selectedRegion);
    }
    return inVisibleScope(events).filter((e: any) => isPanzhihuaAlertEvent(e));
  }, [alertData, view, selectedStoreId, selectedRegion, storeDistrictById, panzhihuaStoreIdSet, storeNameById, visibleStoreIdSet]);

  const alertLevelStats = useMemo(() => {
    const stats = { P1: 0, P2: 0, P3: 0 };
    scopedAlertEvents.forEach((e: any) => {
      const level = String(e?.severity || '').toUpperCase();
      if (level === 'P1' || level === 'P2' || level === 'P3') stats[level] += 1;
    });
    return stats;
  }, [scopedAlertEvents]);

  const salesAlertRows = useMemo(() => {
    const sorted = [...scopedAlertEvents].sort((a: any, b: any) => {
      const rank = (s: any) => {
        const v = String(s || '').toUpperCase();
        if (v === 'P1') return 3;
        if (v === 'P2') return 2;
        if (v === 'P3') return 1;
        return 0;
      };
      return rank(b?.severity) - rank(a?.severity);
    });
    const source =
      view === 'hq'
        ? sorted.filter((e: any) => ['P1', 'P2'].includes(String(e?.severity || '').toUpperCase())).slice(0, 5)
        : sorted.slice(0, 6);
    return source.map((e: any) => ({
      severity: String(e?.severity || 'P2'),
      metric: metricLabel(String(e?.metricCode || '')),
      msg: normalizeAlertMessage(String(e?.message || ''), storeNameById[String(e?.storeId || '')]),
      storeId: String(e?.storeId || ''),
    }));
  }, [scopedAlertEvents, view, storeNameById]);

  const forecastRows = useMemo(() => {
    const src = (forecastData?.weeklyRevenueForecast || []) as any[];
    if (src.length > 0) {
      return src.map((r: any) => ({
        day: String(r?.day || ''),
        baseline: Number(r?.baseline || 0),
        lower: Number(r?.lower || 0),
        upper: Number(r?.upper || 0),
      }));
    }
    const fallback = trendRows.slice(-7);
    return fallback.map((r) => {
      const base = Number(r?.revenue || 0);
      return {
        day: String(r?.date || ''),
        baseline: base,
        lower: Number((base * 0.94).toFixed(2)),
        upper: Number((base * 1.06).toFixed(2)),
      };
    });
  }, [forecastData, trendRows]);

  const forecastOption = useMemo(() => {
    const labels = forecastRows.map((r) => {
      const d = String(r.day || '');
      return d.length >= 10 ? d.slice(5, 10) : d;
    });
    const baseline = forecastRows.map((r) => Number(r.baseline || 0));
    const lower = forecastRows.map((r) => Number(r.lower || 0));
    const upper = forecastRows.map((r) => Number(r.upper || 0));
    return {
      grid: { top: 16, right: 12, left: 42, bottom: 26 },
      legend: {
        top: 0,
        right: 0,
        textStyle: { color: '#8fb9ef', fontSize: 10 },
      },
      xAxis: {
        type: 'category',
        data: labels,
        axisLabel: { color: '#75a8e6', fontSize: 10 },
        axisLine: { lineStyle: { color: 'rgba(99,157,229,.45)' } },
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: '#75a8e6', fontSize: 10 },
        splitLine: { lineStyle: { color: 'rgba(99,157,229,.15)' } },
      },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v: any) => `${formatNumber(v)} 元`,
      },
      series: [
        {
          name: '上界',
          type: 'line',
          data: upper,
          smooth: true,
          symbol: 'none',
          lineStyle: { color: 'rgba(102,200,255,.45)', type: 'dashed' },
        },
        {
          name: '下界',
          type: 'line',
          data: lower,
          smooth: true,
          symbol: 'none',
          lineStyle: { color: 'rgba(102,200,255,.45)', type: 'dashed' },
          areaStyle: {
            color: 'rgba(52, 209, 255, 0.15)',
          },
        },
        {
          name: '预测基线',
          type: 'line',
          data: baseline,
          smooth: true,
          symbol: 'circle',
          symbolSize: 5,
          lineStyle: { color: '#34d1ff', width: 2 },
          itemStyle: { color: '#8ff5ff' },
        },
      ],
      backgroundColor: 'transparent',
    };
  }, [forecastRows]);

  const panelLayout = useMemo(() => {
    if (view === 'store') {
      return {
        leftTopTitle: '当前门店经营快照',
        leftMidTitle: '智能排班建议',
        rightTopTitle: '销售预警',
        rightMidTitle: '门店销售贡献排名',
      };
    }
    if (view === 'region') {
      return {
        leftTopTitle: '区域门店经营快照',
        leftMidTitle: '智能库存（区域）',
        rightTopTitle: '销售预警（区域）',
        rightMidTitle: '区域销售占比',
      };
    }
    return {
      leftTopTitle: '门店经营快照',
      leftMidTitle: '成本与损耗预警',
      rightTopTitle: '销售预警（全局）',
      rightMidTitle: 'AI促销建议',
    };
  }, [view]);

  const costRiskRows = useMemo(() => {
    const raw = [
      ...asArray(costRiskData?.items),
      ...asArray(costRiskData?.list),
      ...asArray(costRiskData?.rows),
      ...asArray(costRiskData?.riskItems),
      ...asArray(costRiskData?.data),
    ];
    return raw.slice(0, 8).map((item: any) => ({
      metric: snackRetailizeCopy(String(item?.metric || item?.metricName || item?.name || '风险指标')),
      current: String(item?.current ?? item?.currentValue ?? item?.value ?? '-'),
      threshold: String(item?.threshold ?? item?.limit ?? item?.target ?? '-'),
      level: String(item?.level || item?.riskLevel || item?.severity || 'P3').toUpperCase(),
    }));
  }, [costRiskData]);

  const staffAdviceRows = useMemo(() => {
    const raw = [
      ...asArray(staffEfficiencyData?.items),
      ...asArray(staffEfficiencyData?.list),
      ...asArray(staffEfficiencyData?.rows),
      ...asArray(staffEfficiencyData?.data),
    ];
    return raw.slice(0, 8).map((item: any) => ({
      metric: snackRetailizeCopy(String(item?.metric || item?.metricName || item?.name || '排班指标')),
      current: String(item?.current ?? item?.currentValue ?? item?.value ?? '-'),
      threshold: String(item?.threshold ?? item?.limit ?? item?.target ?? '-'),
      level: String(item?.level || item?.riskLevel || item?.severity || 'P3').toUpperCase(),
    }));
  }, [staffEfficiencyData]);

  const inventoryV1Rows = useMemo(() => {
    const inventoryEvents = scopedAlertEvents.filter((e: any) =>
      /库存|断货|缺货|临期|滞销/i.test(`${e?.metricCode || ''} ${e?.message || ''}`),
    );
    const regionStores = pzhStoreRank
      .filter((s: any) =>
        view === 'region' && selectedRegion
          ? pickDistrictFromStore(s) === selectedRegion
          : true,
      )
      .slice(0, 6);
    const topStores = regionStores.slice(0, 3).map((s: any) => ({
      label: `${s?.storeName || '-'} 库存风险`,
      value: `销售额 ${formatNumber(s?.revenue || 0)} 元`,
      level: 'P2',
    }));
    if (inventoryEvents.length > 0) {
      const eventRows = inventoryEvents.slice(0, 3).map((e: any) => ({
        label: metricLabel(String(e?.metricCode || '')) || String(e?.metricCode || '库存异常'),
        value: normalizeAlertMessage(String(e?.message || '-'), storeNameById[String(e?.storeId || '')]),
        level: String(e?.severity || 'P2'),
      }));
      return [
        { label: '库存相关预警', value: `${inventoryEvents.length} 条`, level: inventoryEvents.some((e: any) => String(e?.severity || '').toUpperCase() === 'P1') ? 'P1' : 'P2' },
        ...eventRows,
      ];
    }
    if (topStores.length > 0) {
      return [{ label: '库存相关预警', value: '0 条', level: 'P3' }, ...topStores];
    }
    return [{ label: '库存相关预警', value: '暂无数据', level: 'P3' }];
  }, [scopedAlertEvents, pzhStoreRank, view, selectedRegion, storeNameById, metricLabel]);

  const campaignAdviceRows = useMemo(() => {
    const channels = [
      ...asArray(campaignData?.channelStats),
      ...asArray(campaignData?.channels),
      ...asArray(campaignData?.items),
      ...asArray(campaignData?.list),
      ...asArray(campaignData?.data),
    ].slice(0, 8);
    if (channels.length === 0) {
      return [{ level: 'P3', name: '暂无渠道数据', suggestion: '请检查活动投放数据导入', roi: '-' }];
    }
    const rows = channels.map((c: any) => {
      const roi = Number(c?.roi ?? c?.roiValue ?? c?.returnRate ?? 0);
      const spend = Number(c?.spend ?? c?.cost ?? c?.budget ?? c?.amount ?? 0);
      let suggestion = '维持投放';
      let level = 'P3';
      if (roi >= 2.2) {
        suggestion = `建议增投（预算+10%）`;
        level = 'P2';
      } else if (roi < 1.2 && spend > 0) {
        suggestion = `建议降投（预算-15%）`;
        level = 'P1';
      }
      return {
        level,
        name: String(c?.channel || c?.channelName || c?.name || '未知渠道'),
        suggestion,
        roi: formatNumber(roi, 2),
      };
    });
    return rows;
  }, [campaignData]);

  const hqRegionRankRows = useMemo(() => {
    const counts: Record<string, number> = {};
    const countSrc =
      ((geoScope?.stores || []) as any[]).length > 0 ? ((geoScope?.stores || []) as any[]) : storeRank || [];
    countSrc.forEach((s: any) => {
      const d = pickDistrictFromStore(s);
      if (!d) return;
      counts[d] = (counts[d] || 0) + 1;
    });
    return (PANZHIHUA_DISTRICTS as readonly string[])
      .map((d) => ({
        name: d,
        revenue: Number(districtRevenue[d] || 0),
        storeCount: Number(counts[d] || 0),
      }))
      .sort((a, b) => b.revenue - a.revenue);
  }, [districtRevenue, geoScope?.stores, storeRank]);

  const hqRegionPieOption = useMemo(() => {
    const rows = (hqRegionRankRows || []).filter((d: any) => Number(d?.revenue || 0) > 0);
    const total = rows.reduce((acc: number, r: any) => acc + Number(r?.revenue || 0), 0);
    const data = (rows.length ? rows : (hqRegionRankRows || [])).map((r: any) => ({
      name: String(r?.name || '-'),
      value: Number(r?.revenue || 0),
      storeCount: Number(r?.storeCount || 0),
    }));
    return {
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        formatter: (p: any) => {
          const v = Number(p?.value || 0);
          const pct = total > 0 ? ((v / total) * 100).toFixed(1) : '0.0';
          const sc = Number(p?.data?.storeCount || 0);
          return `${p?.name || '-'}<br/>销售额：${formatNumber(v)} 元<br/>占比：${pct}%<br/>门店数：${sc} 店`;
        },
      },
      legend: {
        top: 8,
        left: 8,
        orient: 'vertical',
        textStyle: { color: 'rgba(214,239,255,.9)', fontSize: 12 },
        itemWidth: 10,
        itemHeight: 10,
      },
      series: [
        {
          name: '区域贡献',
          type: 'pie',
          radius: ['52%', '74%'],
          center: ['64%', '58%'],
          avoidLabelOverlap: true,
          itemStyle: {
            borderColor: 'rgba(6, 22, 53, 0.9)',
            borderWidth: 2,
          },
          label: {
            show: false,
          },
          emphasis: {
            label: {
              show: true,
              fontSize: 12,
              color: '#e8f5ff',
              formatter: (p: any) => {
                const v = Number(p?.value || 0);
                const pct = total > 0 ? ((v / total) * 100).toFixed(1) : '0.0';
                return `${p?.name || '-'}\n${pct}%`;
              },
            },
          },
          labelLine: { show: false },
          data,
        },
      ],
      color: ['#62d7ff', '#3f97ff', '#5f7cff', '#37c8a9', THEME.yellow],
    };
  }, [hqRegionRankRows]);

  const hqStoreRiskLevelById = useMemo(() => {
    const rank = (lv: string) => (lv === 'P1' ? 3 : lv === 'P2' ? 2 : lv === 'P3' ? 1 : 0);
    const best: Record<string, string> = {};
    scopedAlertEvents.forEach((e: any) => {
      const id = String(e?.storeId || '');
      if (!id) return;
      const lv = String(e?.severity || '').toUpperCase();
      if (!best[id] || rank(lv) > rank(best[id])) best[id] = lv;
    });
    return best;
  }, [scopedAlertEvents]);

  const hqStoreRankPzh = useMemo(() => {
    return pzhStoreRank;
  }, [pzhStoreRank]);

  const hqTopStores = useMemo(() => {
    return [...hqStoreRankPzh]
      .sort((a: any, b: any) => Number(b?.revenue || 0) - Number(a?.revenue || 0))
      .slice(0, 5);
  }, [hqStoreRankPzh]);

  const hqBottomStores = useMemo(() => {
    const riskRank = (lv: string) => (lv === 'P1' ? 3 : lv === 'P2' ? 2 : lv === 'P3' ? 1 : 0);
    const list = [...hqStoreRankPzh].sort((a: any, b: any) => {
      const la = String(hqStoreRiskLevelById[String(a?.storeId || '')] || '').toUpperCase();
      const lb = String(hqStoreRiskLevelById[String(b?.storeId || '')] || '').toUpperCase();
      const ra = riskRank(la);
      const rb = riskRank(lb);
      if (rb !== ra) return rb - ra;
      return Number(b?.revenue || 0) - Number(a?.revenue || 0);
    });
    // Bottom：优先挑出有 P1/P2 的高销售额门店；若不足再补充销售额较低门店
    const riskFirst = list.filter((s: any) => {
      const lv = String(hqStoreRiskLevelById[String(s?.storeId || '')] || '').toUpperCase();
      return lv === 'P1' || lv === 'P2';
    });
    if (riskFirst.length >= 5) return riskFirst.slice(0, 5);
    const rest = list
      .filter((s: any) => !(riskFirst as any[]).includes(s))
      .sort((a: any, b: any) => Number(a?.revenue || 0) - Number(b?.revenue || 0));
    return [...riskFirst, ...rest].slice(0, 5);
  }, [hqStoreRankPzh, hqStoreRiskLevelById]);

  const fillToN = (arr: any[], n: number, emptyFactory: (idx: number) => any) => {
    const out = [...arr];
    while (out.length < n) out.push(emptyFactory(out.length));
    return out.slice(0, n);
  };

  return (
    <div className="biOpRoot" ref={rootRef} style={viewportHeight ? { height: viewportHeight } : undefined}>
      <div className="biOpHeader">
        <div className="biOpHeaderLeft">当前时刻：{lastUpdatedAt || '-'}</div>
        <div className="biOpHeaderControls">
          <ConfigProvider theme={BI_OP_HEADER_THEME}>
            <Space wrap={false} size={[6, 4]} className="biOpHeaderToolbar">
              <Segmented
                size="small"
                value={view}
                options={[
                  { value: 'hq', label: '总部视角' },
                  { value: 'store', label: '门店视角' },
                  { value: 'region', label: '区域视角' },
                ]}
                onChange={(v) => setView(v as ViewKey)}
              />
              {view === 'store' ? (
                <Select
                  size="small"
                  style={{ width: 'min(156px, 24vw)' }}
                  value={selectedStoreId}
                  options={storeOptions}
                  placeholder="选择门店"
                  onChange={setSelectedStoreId}
                  popupClassName="biOpSelectDropdown"
                  getPopupContainer={() => document.body}
                />
              ) : null}
              {view === 'region' ? (
                <Select
                  size="small"
                  style={{ width: 'min(132px, 20vw)' }}
                  value={selectedRegion}
                  options={regionOptions}
                  placeholder="选择区县"
                  onChange={setSelectedRegion}
                  popupClassName="biOpSelectDropdown"
                  getPopupContainer={() => document.body}
                />
              ) : null}
              <Select
                size="small"
                style={{ width: 'min(280px, 38vw)' }}
                placeholder="选择「图表管理与回看」中的图表（可选）"
                value={selectedChartId}
                options={myChartOptions}
                onChange={setSelectedChartId}
                popupClassName="biOpSelectDropdown"
                getPopupContainer={() => document.body}
              />
              <DatePicker.RangePicker
                size="small"
                value={[dayjs(dateRange[0]), dayjs(dateRange[1])]}
                onChange={(dates) => {
                  if (!dates || !dates[0] || !dates[1]) return;
                  setDateRange([dates[0].format('YYYY-MM-DD'), dates[1].format('YYYY-MM-DD')]);
                }}
                popupClassName="biOpPickerDropdown"
                getPopupContainer={() => document.body}
              />
              <Button
                size="small"
                type="default"
                onClick={() => history.push('/risk_portrait/overview')}
              >
                画像总览
              </Button>
              <Button
                size="small"
                type="primary"
                disabled={view !== 'store' || !selectedStoreId}
                onClick={() =>
                  history.push(
                    buildStorePortraitPath(String(selectedStoreId), storeNameById[String(selectedStoreId)] || ''),
                  )
                }
              >
                当前门店画像
              </Button>
            </Space>
          </ConfigProvider>
        </div>
        <div className="biOpHeaderRight">运行状态：量贩零食经营监控中</div>
      </div>
      <div className="biOpTopStats">
        {kpiCards.map((k) => (
          <div className="biOpTopStat" key={k.label}>
            <div className="label">{k.label}</div>
            <div className="value">{k.value}</div>
            <div className="sub">{k.sub}</div>
          </div>
        ))}
      </div>

      {loading ? (
        <div className="biOpBody">
          <div className={`biOpGrid ${view === 'hq' ? 'biOpGrid--hq' : ''}`}>
            <div className={`biOpLeft ${view === 'hq' ? 'biOpLeft--hq' : ''}`}>
              <Skeleton active paragraph={{ rows: 5 }} style={{ marginBottom: 16 }} />
              <Skeleton active paragraph={{ rows: 4 }} style={{ marginBottom: 16 }} />
              <Skeleton active paragraph={{ rows: 3 }} />
            </div>
            <div className="biOpMid">
              <div style={{ minHeight: 420 }}>
                <Skeleton active title={{ width: '40%' }} paragraph={{ rows: 14 }} />
              </div>
            </div>
            <div className={`biOpRight ${view === 'hq' ? 'biOpRight--hq' : ''}`}>
              <Skeleton active paragraph={{ rows: 5 }} style={{ marginBottom: 16 }} />
              <Skeleton active paragraph={{ rows: 4 }} style={{ marginBottom: 16 }} />
              <Skeleton active paragraph={{ rows: 3 }} />
            </div>
          </div>
        </div>
      ) : (
        <div className="biOpBody">
          <div className={`biOpGrid ${view === 'hq' ? 'biOpGrid--hq' : ''}`}>
            <div className={`biOpLeft ${view === 'hq' ? 'biOpLeft--hq' : ''} ${view === 'store' ? 'biOpLeft--store' : ''}`}>
              <div className="biOpPanel biOpPanelTop">
                <div className="biOpPanelTitle">{view === 'hq' ? '门店Top/Bottom榜' : panelLayout.leftTopTitle}</div>
                {view === 'hq' ? (
                  <div className="biOpHqRankWrap">
                    <div className="sec">
                      <div className="secTitle">Top 5（销售额）</div>
                      <div className="secList">
                        {fillToN(hqTopStores, 5, (i) => ({ _empty: true, storeId: `e-top-${i}` })).map((s: any, idx: number) => (
                          <div className="row" key={String(s?.storeId || s?._empty || idx)}>
                            <div className="left">
                              <span className="idx">{idx + 1}</span>
                              <span className="nm">{s?._empty ? '—' : (s?.storeName || '-')}</span>
                            </div>
                            <div className="right">
                              {s?._empty ? (
                                '—'
                              ) : (
                                <>
                                  <span>{`${formatNumber(s?.revenue || 0)} 元`}</span>
                                  <Button
                                    type="link"
                                    size="small"
                                    style={{ padding: '0 4px', color: THEME.accent }}
                                    onClick={() =>
                                      history.push(buildStorePortraitPath(String(s.storeId), String(s?.storeName || '')))
                                    }
                                  >
                                    画像
                                  </Button>
                                </>
                              )}
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                    <div className="sec">
                      <div className="secTitle">Bottom 5（风险优先）</div>
                      <div className="secList">
                        {fillToN(hqBottomStores, 5, (i) => ({ _empty: true, storeId: `e-btm-${i}` })).map((s: any, idx: number) => {
                          const lv = String(hqStoreRiskLevelById[String(s?.storeId || '')] || '').toUpperCase() || 'P3';
                          return (
                            <div className="row" key={String(s?.storeId || s?._empty || idx)}>
                              <div className="left">
                                <span className="idx">{idx + 1}</span>
                                <span className="nm">{s?._empty ? '—' : (s?.storeName || '-')}</span>
                              </div>
                              <div className="right">
                                {s?._empty ? (
                                  '—'
                                ) : (
                                  <>
                                    <span className={`tag ${levelClassName(lv)}`}>{lv}</span>
                                    <span className="amt">{formatNumber(s?.revenue || 0)} 元</span>
                                    <Button
                                      type="link"
                                      size="small"
                                      style={{ padding: '0 4px', color: THEME.accent }}
                                      onClick={() =>
                                        history.push(buildStorePortraitPath(String(s.storeId), String(s?.storeName || '')))
                                      }
                                    >
                                      画像
                                    </Button>
                                  </>
                                )}
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  </div>
                ) : (
                  <div className="biOpMonitorGrid">
                    {(view === 'region'
                      ? pzhStoreRank.filter((item: any) => pickDistrictFromStore(item) === selectedRegion)
                      : view === 'store' && selectedStoreId
                        ? pzhStoreRank.filter((item: any) => String(item?.storeId) === String(selectedStoreId))
                        : pzhStoreRank
                    )
                      .slice(0, 4)
                      .map((item: any, idx: number) => (
                      <div className="biOpMonitorItem" key={`${item?.storeId}-${idx}`}>
                        <div className="name">{item?.storeName || `门店-${idx + 1}`}</div>
                        <div className="desc">销售额 {formatNumber(item?.revenue || 0)} 元</div>
                      </div>
                      ))}
                  </div>
                )}
              </div>
              <div className="biOpPanel biOpPanelMid">
                <div className="biOpPanelTitle">{panelLayout.leftMidTitle}</div>
                {view === 'hq' ? (
                  <div className="biOpMiniList">
                    {(costRiskRows.length
                      ? costRiskRows
                      : [{ metric: '成本风险', current: '-', threshold: '-', level: 'P3' }]
                    ).map((r: any, idx: number) => (
                      <div className="biOpMiniRow" key={`${r.metric}-${idx}`}>
                        <div className="name">
                          <span className={`biOpLevelTag ${levelClassName(r.level)}`}>{r.level}</span>
                          <span>{r.metric}</span>
                        </div>
                        <div className="val">{`${r.current} / ${r.threshold}`}</div>
                      </div>
                    ))}
                  </div>
                ) : null}
                {view === 'region' ? (
                  <div className="biOpMiniList">
                    {inventoryV1Rows.map((r: any, idx: number) => (
                      <div className="biOpMiniRow" key={`${r.label}-${idx}`}>
                        <div className="name">
                          <span className={`biOpLevelTag ${levelClassName(r.level)}`}>{r.level}</span>
                          <span>{r.label}</span>
                        </div>
                        <div className="val">{r.value}</div>
                      </div>
                    ))}
                  </div>
                ) : null}
                {view === 'store' ? (
                  <div className="biOpMiniList">
                    {(staffAdviceRows.length
                      ? staffAdviceRows
                      : [{ metric: '排班建议', current: '-', threshold: '-', level: 'P3' }]
                    ).map((r: any, idx: number) => (
                      <div className="biOpMiniRow" key={`${r.metric}-${idx}`}>
                        <div className="name">
                          <span className={`biOpLevelTag ${levelClassName(r.level)}`}>{r.level}</span>
                          <span>{r.metric}</span>
                        </div>
                        <div className="val">{`${r.current} / ${r.threshold}`}</div>
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
              <div className="biOpPanel biOpPanelBottom">
                <div className="biOpPanelTitle">门店销售趋势</div>
                <ReactECharts option={leftTrendOption} style={{ height: 136 }} />
              </div>
            </div>

            <div className="biOpMid">
              <div className="biOpPanel biOpMapPanel">
                <div className="biOpPanelTitle">地理分布总览</div>
                <div className="biOpMapMain" ref={mapMainRef}>
                  <div className="biOpMapAmbient" aria-hidden />
                  <div className="biOpMapSweep" aria-hidden />
                  {mapReady ? (
                    <ReactECharts
                      // 切视角时重建地图实例，清掉上个视角残留的缩放/平移状态
                      key={`map-2d-${view}-${selectedStoreId || 'all'}-${selectedRegion || 'all'}`}
                      option={mapOption}
                      style={{ height: '100%', width: '100%' }}
                      opts={{ renderer: 'canvas' }}
                      notMerge
                    />
                  ) : (
                    <div className="biOpMapLoading">
                      <Spin tip="地图加载中…" />
                    </div>
                  )}
                  <div className="biOpMapMeta">
                    区县边界：{mapReady ? `${(mapMeta?.districts || []).length}/${PANZHIHUA_DISTRICTS.length}` : '加载中…'}
                    ｜ 门店点：{scopedStoreMapPoints.length}
                    ｜ 门店排行：
                    {view === 'store' && selectedStoreId
                      ? effectiveStoreRank.filter((s: any) => String(s?.storeId) === String(selectedStoreId)).length
                      : effectiveStoreRank.length}
                  </div>
                </div>
                <div className="biOpEventTicker">
                  {(alertRows.length ? alertRows : [{ severity: 'P2', metric: '系统状态', msg: '暂无某市库存预警事件' }]).map((it: any, idx: number) => (
                    <div className="event" key={`${it.metric}-${idx}`}>
                      <span className="tag">{it.severity}</span>
                      <span className="txt">{it.metric}：{it.msg || '-'}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            <div className={`biOpRight ${view === 'hq' ? 'biOpRight--hq' : ''} ${view === 'store' ? 'biOpRight--store' : ''}`}>
              <div className="biOpPanel biOpPanelTop">
                <div className="biOpPanelTitle">{panelLayout.rightTopTitle}</div>
                <div className="biOpMiniList">
                  <div className="biOpMiniRow">
                    <div className="name">预警总览</div>
                    <div className="val">P1：{alertLevelStats.P1} ｜ P2：{alertLevelStats.P2} ｜ P3：{alertLevelStats.P3}</div>
                  </div>
                  {(salesAlertRows.length ? salesAlertRows : [{ severity: 'P3', metric: '系统状态', msg: '当前无销售预警事件' }]).map((d: any, idx: number) => (
                    <div className="biOpMiniRow" key={`${d?.metric}-${idx}`}>
                      <div className="name">
                        <span className={`biOpLevelTag ${levelClassName(d?.severity)}`}>{d?.severity || 'P3'}</span>
                        <span>{d?.metric || '-'}</span>
                      </div>
                      <div className="val" style={{ textAlign: 'right' }}>
                        <div>{d?.msg || '-'}</div>
                        {d?.storeId ? (
                          <Button
                            type="link"
                            size="small"
                            style={{ padding: 0, height: 'auto', color: THEME.accent }}
                            onClick={() =>
                              history.push(
                                buildStorePortraitPath(d.storeId, storeNameById[d.storeId] || ''),
                              )
                            }
                          >
                            门店画像
                          </Button>
                        ) : null}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
              <div className="biOpPanel biOpPanelMid">
                <div className="biOpPanelTitle">{panelLayout.rightMidTitle}</div>
                {view === 'hq' ? (
                  <div className="biOpMiniList">
                    {campaignAdviceRows.map((d: any, idx: number) => (
                      <div className="biOpMiniRow" key={`${d?.name}-${idx}`}>
                        <div className="name">
                          <span className={`biOpLevelTag ${levelClassName(d?.level)}`}>{d?.level || 'P3'}</span>
                          <span>{`${d?.name}（ROI ${d?.roi}）`}</span>
                        </div>
                        <div className="val">{d?.suggestion}</div>
                      </div>
                    ))}
                  </div>
                ) : null}
                {view === 'store' ? (
                  <div className="biOpMiniList">
                    {(selectedStoreId
                      ? effectiveStoreRank.filter((d: any) => String(d?.storeId) === String(selectedStoreId))
                      : effectiveStoreRank
                    )
                      .filter((d: any) => !!pickDistrictFromStore(d))
                      .slice(0, 6)
                      .map((d: any, idx: number) => (
                        <div className="biOpMiniRow" key={`${d?.storeId}-${idx}`}>
                          <span className="name">{d?.storeName || '-'}</span>
                          <span className="val">{formatNumber(d?.revenue)}</span>
                        </div>
                      ))}
                  </div>
                ) : null}
                {view === 'region' ? (
                  <ReactECharts option={regionRoseOption} style={{ height: 200 }} />
                ) : null}
              </div>
              <div className="biOpPanel biOpPanelBottom">
                <div className="biOpPanelTitle">{view === 'hq' ? '区域贡献排行' : '销售预测（未来7天）'}</div>
                {view === 'hq' ? (
                  <ReactECharts option={hqRegionPieOption} style={{ height: 188 }} />
                ) : (
                  <ReactECharts option={forecastOption} style={{ height: 158 }} />
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default BIOperationScreen;

