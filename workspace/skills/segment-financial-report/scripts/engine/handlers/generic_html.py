# -*- coding: utf-8 -*-
"""GenericHtmlLayoutHandler：通用兜底 handler，委托给 DataExtractor。"""
from __future__ import annotations

from typing import Dict, List, Optional

from ..data_extractor import DataExtractor
from ..model import CompanyConfig, FinancialTable, Segment, SegmentMetric


class GenericHtmlLayoutHandler:
    priority_value = 999

    def __init__(self, data_extractor: DataExtractor):
        self.data_extractor = data_extractor

    def priority(self) -> int:
        return self.priority_value

    def supports(self, table: FinancialTable, cfg: Optional[CompanyConfig]) -> bool:
        return True

    def apply(self, table: FinancialTable, cfg: Optional[CompanyConfig],
              sink: Dict[str, Segment]) -> int:
        segments = self.data_extractor.extractSegmentData(table)
        if not segments:
            return 0
        hits = 0

        # Determine which segmentCodes are level-1 (top-level) in the config so we
        # don't accidentally promote L2/L3 children to the top level of the sink.
        # The extractor already builds parent-child relationships via
        # _build_hierarchy(); non-L1 segments appear both in the flat list AND as
        # children of their parents.  We only merge the L1 segments; the children
        # come along via their parent's merge.
        l1_codes: set = set()
        parent_map: Dict[str, str] = {}  # child_code -> parent_code
        if cfg is not None and cfg.segments:
            for sc in cfg.segments:
                if sc.level == 1:
                    l1_codes.add(sc.segmentCode)
                if sc.parentCode:
                    parent_map[sc.segmentCode] = sc.parentCode

        # Collect segments by code for lookup
        by_code: Dict[str, Segment] = {}
        for s in segments:
            if s.segmentCode:
                by_code[s.segmentCode] = s

        for src in segments:
            code = src.segmentCode
            if not code:
                continue
            # Skip non-L1 segments; they are already children of some L1 in the
            # segments list and will be merged through that parent.
            if l1_codes and code not in l1_codes:
                continue
            dst = sink.get(code)
            if dst is None:
                sink[code] = src
                hits += self._count(src)
            else:
                hits += self._merge_into(dst, src)

        # If there were orphaned non-L1 segments whose L1 parent was NOT in the
        # returned list (e.g. a sub-segment table where the L1 header row does
        # not appear), attach them under a shell L1 segment so data is not lost.
        if l1_codes:
            for src in segments:
                code = src.segmentCode
                if not code or code in l1_codes:
                    continue
                # Check if this segment is already reachable from the sink
                if self._is_reachable(code, sink):
                    continue
                # Find the L1 ancestor
                l1_ancestor = self._find_l1_ancestor(code, parent_map, l1_codes)
                if l1_ancestor is None:
                    # No known ancestor; promote to sink as-is (defensive)
                    if code not in sink:
                        sink[code] = src
                        hits += self._count(src)
                    else:
                        hits += self._merge_into(sink[code], src)
                    continue
                # Find/create the L1 ancestor in sink
                ancestor = sink.get(l1_ancestor)
                if ancestor is None:
                    ancestor = Segment()
                    ancestor.segmentCode = l1_ancestor
                    # Fill in display name from config if available
                    if cfg is not None and cfg.segments:
                        for sc in cfg.segments:
                            if sc.segmentCode == l1_ancestor:
                                ancestor.segmentName = sc.segmentName
                                ancestor.level = 1
                                break
                    sink[l1_ancestor] = ancestor
                # Walk down from the L1 ancestor to the correct parent, creating
                # intermediate shells as needed, then merge src in.
                chain = self._build_chain(code, parent_map, l1_codes)
                # chain is [l1, ..., direct_parent_of_src], reversed
                direct_parent = ancestor
                for intermediate_code in chain[1:-1] if len(chain) > 1 else []:
                    direct_parent = self._get_or_create_child(direct_parent, intermediate_code, cfg)
                # Now merge src into direct_parent's children
                existing = None
                for c in direct_parent.children:
                    if c.segmentCode == code:
                        existing = c
                        break
                if existing is None:
                    direct_parent.addChild(src)
                    hits += self._count(src)
                else:
                    hits += self._merge_into(existing, src)
        return hits

    @staticmethod
    def _is_reachable(code: str, sink: Dict[str, Segment]) -> bool:
        """Return True if *code* is a key in sink or reachable via children of any sink entry."""
        if code in sink:
            return True
        visited = set()

        def _walk(seg: Segment) -> bool:
            if seg.segmentCode in visited:
                return False
            visited.add(seg.segmentCode or "")
            for c in seg.children:
                if c.segmentCode == code:
                    return True
                if _walk(c):
                    return True
            return False

        for s in sink.values():
            if _walk(s):
                return True
        return False

    @staticmethod
    def _find_l1_ancestor(code: str, parent_map: Dict[str, str], l1_codes: set) -> Optional[str]:
        """Walk up parent_map to find the L1 ancestor of *code*."""
        visited = set()
        cur = code
        while cur and cur not in visited:
            visited.add(cur)
            if cur in l1_codes:
                return cur
            cur = parent_map.get(cur)
        return None

    @staticmethod
    def _build_chain(code: str, parent_map: Dict[str, str], l1_codes: set) -> List[str]:
        """Return [l1_ancestor, ..., code] from L1 down to *code*."""
        chain = [code]
        visited = set()
        cur = parent_map.get(code)
        while cur and cur not in visited:
            visited.add(cur)
            chain.append(cur)
            if cur in l1_codes:
                break
            cur = parent_map.get(cur)
        chain.reverse()
        return chain

    @staticmethod
    def _get_or_create_child(parent: Segment, child_code: str,
                             cfg: Optional[CompanyConfig]) -> Segment:
        """Find child under parent by code; create a shell if not present."""
        for c in parent.children:
            if c.segmentCode == child_code:
                return c
        new_seg = Segment()
        new_seg.segmentCode = child_code
        if cfg is not None and cfg.segments:
            for sc in cfg.segments:
                if sc.segmentCode == child_code:
                    new_seg.segmentName = sc.segmentName
                    new_seg.level = sc.level
                    break
        parent.addChild(new_seg)
        return new_seg

    def _merge_into(self, dst: Segment, src: Segment) -> int:
        added = 0
        for m in src.metrics:
            if dst.getMetric(m.metricCode, m.period) is None:
                dst.addMetric(m)
                added += 1
        for src_child in src.children:
            existing = None
            for c in dst.children:
                if (src_child.segmentCode is not None
                        and c.segmentCode is not None
                        and src_child.segmentCode.lower() == c.segmentCode.lower()):
                    existing = c
                    break
            if existing is None:
                dst.addChild(src_child)
                added += self._count(src_child)
            else:
                added += self._merge_into(existing, src_child)
        return added

    def _count(self, s: Segment) -> int:
        n = len(s.metrics)
        for c in s.children:
            n += self._count(c)
        return n
