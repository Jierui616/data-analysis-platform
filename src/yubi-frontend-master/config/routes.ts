export default [
  { path: '/user', layout: false, routes: [{ path: '/user/login', component: './User/Login' }] },
  { path: '/', redirect: '/ai/realtime' },

  /** 兼容旧链接 */
  { path: '/add_chart', redirect: '/ai/realtime', hideInMenu: true },
  { path: '/add_chart_async', redirect: '/ai/async', hideInMenu: true },
  { path: '/deep_chat', redirect: '/ai/chat', hideInMenu: true },
  { path: '/my_chart', redirect: '/biz/charts', hideInMenu: true },
  { path: '/lineage_center', redirect: '/lineage/trace', hideInMenu: true },
  { path: '/ai/auto-eda', redirect: '/lineage/explore', hideInMenu: true },
  { path: '/biz/lineage', redirect: '/lineage/trace', hideInMenu: true },
  { path: '/data_import', redirect: '/biz/import', hideInMenu: true },
  { path: '/bi_bigscreen', redirect: '/biz/bigscreen', hideInMenu: true },
  { path: '/insight-report/:sessionId', redirect: '/lineage/report/:sessionId', hideInMenu: true },

  /** 智能分析（平铺菜单，无折叠分组） */
  { path: '/ai/realtime', name: '实时生成', icon: 'thunderbolt', component: './AddChart' },
  { path: '/ai/async', name: '异步生成', icon: 'clockCircle', component: './AddChartAsync' },
  { path: '/ai/chat', name: '深度对话', icon: 'message', component: './DeepChat' },

  /** 数据血缘（平铺：追踪 / 智能探索 / 探索记录） */
  { path: '/lineage/trace', name: '血缘追踪', icon: 'apartment', component: './LineageCenter' },
  { path: '/lineage/explore', name: '智能探索', icon: 'compass', component: './AutoEDA' },
  { path: '/lineage/history', name: '探索记录', icon: 'history', component: './ExplorationHistory' },
  { path: '/lineage/report/:sessionId', name: '探索报告', component: './InsightReport', hideInMenu: true },

  /** 数据与经营 */
  { path: '/biz/charts', name: '图表管理', icon: 'pieChart', component: './MyChart' },
  { path: '/biz/import', name: '成本排班导入', icon: 'upload', component: './DataImportCenter' },
  { path: '/biz/bigscreen', name: '经营大屏', icon: 'dashboard', component: './BIOperationScreen' },

  { path: '/chart_result/:id', hideInMenu: true, component: './ChartResult' },

  {
    path: '/risk_portrait',
    name: '风险画像',
    icon: 'idcard',
    routes: [
      { path: '/risk_portrait', redirect: '/risk_portrait/overview' },
      { path: '/risk_portrait/overview', name: '画像总览', component: './RiskPortrait/Overview' },
      { path: '/risk_portrait/store', name: '门店画像', component: './RiskPortrait/Store' },
      { path: '/risk_portrait/member', name: '会员画像', component: './RiskPortrait/Member' },
      { path: '/risk_portrait/anonymous', name: '匿名画像', component: './RiskPortrait/Anonymous' },
      { path: '/risk_portrait/rules', name: '策略规则', component: './RiskPortrait/Rules' },
      { path: '/risk_portrait/audit', name: '命中留痕', component: './RiskPortrait/Audit' },
    ],
  },

  {
    path: '/admin',
    name: '系统管理',
    icon: 'crown',
    access: 'canAdmin',
    routes: [
      { path: '/admin', redirect: '/admin/sub-page' },
      { path: '/admin/sub-page', name: '管理控制台', component: './Admin' },
    ],
  },
  { path: '*', layout: false, component: './404' },
];
