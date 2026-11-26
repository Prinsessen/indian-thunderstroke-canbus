"""A TPU sleeve for the CANFD-MC board on the Springfield (2026-11-24, the owner: "a flexible TPU enclosure, as
flexible as the heat shrink, that does not rattle with the vibrations like ASA would - no thicker than the heat
shrink").

v4 (2026-11-25), the owner's design after the first red TPU print:
  - the TUBE is v2's, unchanged - the one already printed: open at one end, closed at the other. Everything leaves
    through the open end: the four wires AND the antenna coax ("alle 5 ledninger incl. antenne skal komme ud i den
    ende som v2 kom ud"). The board goes in ESP32 end first, so its WIRE end is at the opening (the pads
    CANL/CANH/GND/VBAT 12-32 mm in, the wires under the board);
  - the four wires (0.5 mm2, 2.0 mm each) come out of a nylon braid as one round bundle; only the bare wires go
    through the plug ("strømpen skal ikke med gennem tyllen"). They are threaded through ONE round hole and a
    CONICAL GROMMET (the owner's idea, a cable gland), pushed into a conical seat in the plug's outer block. The cone
    seals and centres; the grommet's TAIL (four fingers and a cable tie) grips the bundle - the wedge alone did not;
  - inside the plug a funnel takes the wires from the flat row under the board (2.2 mm) up into the round bundle over
    6 mm - v2's 1.3 mm holes right at the board's edge bent each wire back, and the wires pushed the plug off;
  - the coax goes through the SAME grommet (later that evening, the owner: its own slit weakened the plug too much):
    U.FL first, then the four wires; a second mouth of the funnel, a chimney, takes it up over the board's edge;
  - the plug has v3's barb ring (the tube stretches over it) and the cable tie round the skirt. Nothing is cut: the
    knife lines and the outer block's tie groove are left closed, for a bundle that cannot be threaded.
v3 (both ends open, a plug at each end, 3bd8311) is in git history; it was built on a misunderstanding of where the
wires leave.

Fitting: the coax's U.FL through the grommet and the plug's chimney, plug it on the module; slide the board in ESP32
end first; the four wires through the grommet and down the funnel, then solder them; push the plug into the tube;
push the grommet into the seat until it is tight; cable ties round the skirt and the grommet's tail. Opening it
again: cut the ties, pull the grommet, pull the plug. The full description: README.md.

Board from the hardware repo (canfd-mc-hardware, read only): 55 x 32 x 1.6, every part on the top. Heights from the
datasheets: L2 FNR5040S 4.0, WROOM-1U 3.2, L1 DLW43SH 2.7, D2 SMB 2.3. Coordinates: the tube's opening at x = -PLUG_IN,
the board from x = 0.3 (its WIRE end) to 55.3, y = 0 its centre line, +y the antenna plate's side (the pads and L2 are
on that side with the board this way round), z = 0 the board's underside.
Print the tube standing on its open end, the plug on its outer face, the grommet on its small end (brim) - all three
in one job: tpu_sleeve_all.stl; TPU 95A HF from the external spool, its own profile (~230/35), no supports.
"""
import math

import cadquery as cq

BOARD_L, BOARD_W, BOARD_T = 55.0, 32.0, 1.6
# ---- the tube: v2 exactly (already printed in red TPU 95A HF, 2026-11-25) ----
TOP = 4.2              # inside height over the board: L2 4.0 + 0.2 - it touches, softly
BOTTOM = 2.2           # under the board
FIT = 0.3              # board to side wall, each side
WALL = 1.0             # TPU wall (heat shrink is ~0.5-1 mm once shrunk)
END_WALL = 1.4         # the closed end
R_IN = 1.5             # inside corner radius of the cross-section
LEDGE = 1.0            # the ledges the board's long edges rest on
PLUG_IN = 6.0          # how deep the plug goes into the tube
ANTENNA = (41.0, 12.0)        # the flex antenna (the owner 2026-11-24): 41 x 12 mm, coax 14 cm from a long edge's middle
PLATE_T = 1.0          # the side plate's thickness over the tube's side
RECESS = 0.3           # the antenna's seat in the plate, 0.4 mm larger than the antenna
# ---- the plug (v4) ----
PLUG_SQUEEZE = 0.3     # the plug over the tube's inside
BARB = 0.4             # a ring round the plug, 0.4 mm proud, 1.5 mm from its inner end: the tube stretches over it
CAP_T = 1.5            # the plug's outer face
SKIRT_L, SKIRT_T = 5.0, 1.0   # the skirt that grips the tube's end from outside
TIE_W, TIE_DEPTH = 3.5, 0.4   # cable-tie groove round the skirt
GROOVE = 0.5           # the knife lines' depth
WIRE_D = 2.0           # the four wires: 0.5 mm2, 2.0 mm each (the owner)
BUNDLE_D = WIRE_D * (1 + math.sqrt(2))   # four round wires in a 2 x 2 bundle: 4.83 mm round
BUNDLE_Y, BUNDLE_Z = 8.8, 0.6  # the bundle's centre at the seat: over the pads' side, low
WIRE_Y = (4.6, 7.4, 10.2, 13.0)   # the wires under the board, by the pads (y ~ +14 with the board this way round)
FUNNEL_Y = (3.0, 14.6)         # the funnel's mouth under the board's edge, across
COAX_D = 1.4           # the antenna coax (the owner's measure, 1.4) - through the SAME grommet since 2026-11-25 (the owner: its own slit weakened
                       # the plug too much); its U.FL plug (~3.0 x 2.6 x 2.0) goes through the grommet first
COAX_Y, COAX_Z = 6.0, 4.4      # where it runs over the board to the module (clear of L1, y 1-4, and L2, y 10-15)
CHIMNEY_Y = (4.0, 8.0)         # the funnel's second mouth: up over the board's edge for the coax, wide enough for the U.FL
CHIMNEY_TOP = 5.0
# ---- the grommet (the owner's conical "tylle") ----
BORE = 5.6             # the 4 x 2.0 wires roll round the 1.4 coax: 5.40 round (4 in a square with the coax outside, as
                       # drawn first, is 6.15 - the owner got a 5th wire through the 6.4 bore: 5 round the coax is 5.40
                       # too). 5.6 threads; the grip is the tail's cable tie (the wedge alone held nothing, 2026-11-25)
TAPER = math.radians(4.0)      # half angle: a wedge, it stays where it is pushed
G_WALL = 1.35          # its wall at the small end: the outside 8.3 -> 9.4, 0.2 a side over the seat as printed
G_LEN = 8.0            # cone length; the seat is 6, so it stands ~2 mm proud before it is pushed tight
SEAT_LEN = 6.0
SEAT_SQUEEZE = 0.2     # the seat under the grommet, a side (the seat is fixed as printed: SEAT_R0; the grommet follows)
COLLAR_T, COLLAR_W = 1.2, 1.2  # the collar to push it with
TAIL_L, TAIL_WALL = 9.0, 1.2   # the tail beyond the collar: four fingers a cable tie squeezes onto the bundle (a connector's
FINGER_SLOT, SLOT_L = 1.0, 7.0  # strain relief); the slots printed open - nothing is cut
TAIL_TIE_W, TAIL_TIE_DEPTH = 3.0, 0.3
NOZZLE_WALL = 1.3      # around the seat's wide end
NOZZLE_TIE_W = 2.5     # a cable tie round the outer block closes the bundle's slit over the grommet

W_IN = BOARD_W + 2 * FIT
H_IN = BOTTOM + BOARD_T + TOP
L_IN = BOARD_L + 0.6
W_OUT, H_OUT = W_IN + 2 * WALL, H_IN + 2 * WALL
Z0 = -BOTTOM           # board bottom face at z = 0; cavity from Z0 to Z0 + H_IN
ZC = Z0 + H_IN / 2
X_CAP = -PLUG_IN - CAP_T          # the plug's outer face
X_NOZ = -PLUG_IN - SEAT_LEN       # the plug's outer face (the block's)
R_SMALL = BORE / 2 + G_WALL       # the grommet at its small end


def r_g(s):
    """the grommet's radius s mm from its small end"""
    return R_SMALL + s * math.tan(TAPER)


SEAT_R0 = 7.90 / 2         # the seat at its inner end - as printed 2026-11-25 (the grommet is matched to it)
R_NOZ = SEAT_R0 + SEAT_LEN * math.tan(TAPER) + NOZZLE_WALL
FUNNEL_R = 3.3                 # the funnel at the seat (drawn for the 6.4 bore; the plug as printed)


def tube():
    """v2's tube, unchanged: open at x = -PLUG_IN, closed at the far end; the antenna plate on +y"""
    length = PLUG_IN + L_IN + END_WALL
    body = (cq.Workplane("YZ").workplane(offset=-PLUG_IN).center(0, ZC).rect(W_OUT, H_OUT).extrude(length)
            .edges("|X").fillet(R_IN + WALL))
    cavity = (cq.Workplane("YZ").workplane(offset=-PLUG_IN - 1).center(0, ZC).rect(W_IN, H_IN).extrude(PLUG_IN + 1 + L_IN)
              .edges("|X").fillet(R_IN))
    s = body.cut(cavity)
    aw, ah = ANTENNA
    xc = L_IN / 2
    s = s.union(cq.Workplane("XZ").workplane(offset=-(W_OUT / 2 + PLATE_T)).center(xc, ZC)
                .rect(aw + 2.0, ah + 1.0).extrude(PLATE_T + WALL).edges("|Y").fillet(1.0))
    s = s.cut(cq.Workplane("XZ").workplane(offset=-(W_OUT / 2 + PLATE_T) - 0.01).center(xc, ZC)
              .rect(aw + 0.4, ah + 0.4).extrude(RECESS))
    s = s.cut(cq.Workplane("YZ").workplane(offset=-PLUG_IN - 1).center(0, ZC).rect(W_IN, H_IN).extrude(PLUG_IN + 1 + L_IN)
              .edges("|X").fillet(R_IN))
    for side in (-1, 1):
        yy = side * (W_IN / 2 - LEDGE / 2)
        s = s.union(cq.Workplane("XY").workplane(offset=Z0).center(L_IN / 2, yy).rect(L_IN - 4, LEDGE).extrude(BOTTOM))
    return s


def _cone(x_small, x_big, r_small, r_big):
    """a cone along -x: r_small at x_small, r_big at x_big (x_big < x_small), on the bundle's axis"""
    return cq.Workplane("XY").add(cq.Solid.makeCone(r_small, r_big, x_small - x_big,
                                                    cq.Vector(x_small, BUNDLE_Y, BUNDLE_Z), cq.Vector(-1, 0, 0)))


def _cyl(x0, x1, r):
    return cq.Workplane("YZ").workplane(offset=x0).center(BUNDLE_Y, BUNDLE_Z).circle(r).extrude(x1 - x0)


def plug():
    """the plug for the tube's opening: a funnel from the grommet's conical seat in the outer block to under the
    board (the four wires) and up over its edge (the coax)"""
    body = (cq.Workplane("YZ").workplane(offset=-PLUG_IN).center(0, ZC).rect(W_IN + PLUG_SQUEEZE, H_IN + PLUG_SQUEEZE)
            .extrude(PLUG_IN).edges("|X").fillet(R_IN))
    barb = (cq.Workplane("YZ").workplane(offset=-2.5).center(0, ZC)
            .rect(W_IN + PLUG_SQUEEZE + 2 * BARB, H_IN + PLUG_SQUEEZE + 2 * BARB).extrude(1.0).edges("|X").fillet(R_IN + BARB))
    cap = (cq.Workplane("YZ").workplane(offset=X_CAP).center(0, ZC).rect(W_OUT + 2 * SKIRT_T, H_OUT + 2 * SKIRT_T)
           .extrude(CAP_T + SKIRT_L).edges("|X").fillet(R_IN + WALL + SKIRT_T))
    cap = cap.cut(cq.Workplane("YZ").workplane(offset=-PLUG_IN).center(0, ZC).rect(W_OUT + 0.2, H_OUT + 0.2)
                  .extrude(SKIRT_L + 1).edges("|X").fillet(R_IN + WALL))        # the tube's end goes in here
    # the seat's block: the cap made SEAT_LEN thick over its whole face (a round nozzle on a thin cap printed standing
    # on the nozzle with the cap in the air - the owner's first try, 2026-11-25). Flat outer face = the print's bottom.
    zb0 = min(BUNDLE_Z - R_NOZ, ZC - (H_OUT + 2 * SKIRT_T) / 2)
    zb1 = ZC + (H_OUT + 2 * SKIRT_T) / 2
    block = (cq.Workplane("YZ").workplane(offset=X_NOZ).center(0, (zb0 + zb1) / 2).rect(W_OUT + 2 * SKIRT_T, zb1 - zb0)
             .extrude(X_CAP - X_NOZ + 0.01).edges("|X").fillet(R_IN + WALL + SKIRT_T))
    p = cap.union(body).union(barb).union(block)
    tie = (cq.Workplane("YZ").workplane(offset=-PLUG_IN + SKIRT_L - TIE_W - 1).center(0, ZC)
           .rect(W_OUT + 2 * SKIRT_T + 2, H_OUT + 2 * SKIRT_T + 2)
           .rect(W_OUT + 2 * SKIRT_T - 2 * TIE_DEPTH, H_OUT + 2 * SKIRT_T - 2 * TIE_DEPTH).extrude(TIE_W))
    p = p.cut(tie)
    # a cable tie round the block closes the bundle's slit over the grommet
    ntie_x = X_NOZ + (X_CAP - X_NOZ - NOZZLE_TIE_W) / 2
    p = p.cut(cq.Workplane("YZ").workplane(offset=ntie_x).center(0, (zb0 + zb1) / 2)
              .rect(W_OUT + 2 * SKIRT_T + 2, zb1 - zb0 + 2)
              .rect(W_OUT + 2 * SKIRT_T - 2 * TIE_DEPTH, zb1 - zb0 - 2 * TIE_DEPTH).extrude(NOZZLE_TIE_W))
    # the grommet's seat: fixed as printed 2026-11-25 (SEAT_R0), the grommet drawn SEAT_SQUEEZE over it
    p = p.cut(_cone(-PLUG_IN + 0.01, X_NOZ - 0.01, SEAT_R0, SEAT_R0 + SEAT_LEN * math.tan(TAPER)))
    # the funnel: from the bundle's circle at the seat to the flat row under the board's edge
    def mouth(y0, y1, za, zb):
        circle = cq.Wire.makeCircle(FUNNEL_R, cq.Vector(-PLUG_IN, BUNDLE_Y, BUNDLE_Z), cq.Vector(1, 0, 0))
        rect = cq.Wire.makePolygon([cq.Vector(0.6, y0, za), cq.Vector(0.6, y1, za), cq.Vector(0.6, y1, zb),
                                    cq.Vector(0.6, y0, zb)], close=True)
        return cq.Workplane("XY").add(cq.Solid.makeLoft([circle, rect], True))
    p = p.cut(mouth(*FUNNEL_Y, Z0 - PLUG_SQUEEZE, 0.2))          # the four wires: under the board
    p = p.cut(mouth(*CHIMNEY_Y, -0.5, CHIMNEY_TOP))              # the coax: up over the board's edge
    # the bundle's slit: from the seat straight down through block and skirt - knife lines on the block's face
    # and the skirt's end (inside the tube the funnel is open to the bottom already)
    bot_rim = min(BUNDLE_Z - R_NOZ, ZC - (H_OUT + 2 * SKIRT_T) / 2) - 1
    z_top = BUNDLE_Z - SEAT_R0 + 0.01
    for xf, sign in ((X_NOZ, 1), (-PLUG_IN + SKIRT_L, -1)):
        p = p.cut(cq.Workplane("XY").workplane(offset=bot_rim).center(xf + sign * GROOVE / 2, BUNDLE_Y)
                  .rect(GROOVE, GROOVE * 1.2).extrude(z_top - bot_rim))
    return p


def grommet():
    """the conical grommet, its small end at x = -PLUG_IN (where it ends up once pushed in): a round bore for the bundle,
    the cone, a collar (45 degrees on the cone side, so it prints standing on its small end), and a TAIL of four fingers
    with a groove for the cable tie that grips the bundle"""
    x_small = -PLUG_IN
    x_big = x_small - G_LEN
    r_col = r_g(G_LEN) + COLLAR_W
    ch = r_col - r_g(G_LEN)
    g = _cone(x_small, x_big, r_g(0), r_g(G_LEN))
    g = g.union(_cone(x_big + 0.01, x_big - ch, r_g(G_LEN), r_col))
    xc = x_big - ch - COLLAR_T                       # the collar's outer face, where the tail starts
    g = g.union(_cyl(xc, x_big - ch + 0.01, r_col))
    r_tail = BORE / 2 + TAIL_WALL
    x_end = xc - TAIL_L
    g = g.union(_cyl(x_end, xc + 0.01, r_tail))
    g = g.cut(_cyl(x_end - 1, x_small + 1, BORE / 2))
    for a in (45, 135):                              # four fingers: two slots right across, at 45 degrees
        slot = (cq.Workplane("YZ").workplane(offset=x_end - 1).center(BUNDLE_Y, BUNDLE_Z)
                .rect(2 * r_tail + 2, FINGER_SLOT).extrude(SLOT_L + 1))
        g = g.cut(slot.rotate((0, BUNDLE_Y, BUNDLE_Z), (1, BUNDLE_Y, BUNDLE_Z), a))
    xt = x_end + 1.5
    g = g.cut(_cyl(xt, xt + TAIL_TIE_W, r_tail + 1).cut(_cyl(xt, xt + TAIL_TIE_W, r_tail - TAIL_TIE_DEPTH)))
    return g


def board():
    """the board, wire end at x = 0.3, and its tallest part L2 (5 x 5 x 4.0) by the opening on +y"""
    b = cq.Workplane("XY").center(0.3 + BOARD_L / 2, 0).rect(BOARD_L, BOARD_W).extrude(BOARD_T)
    return b.union(cq.Workplane("XY").workplane(offset=BOARD_T).center(3.6, 12.6).rect(5, 5).extrude(4.0))


def _rod(pts, r):
    s = None
    for a, b in zip(pts[:-1], pts[1:]):
        a, b = cq.Vector(*a), cq.Vector(*b)
        v = b - a
        c = cq.Workplane("XY").add(cq.Solid.makeCylinder(r, v.Length, a, v.normalized()))
        c = c.union(cq.Workplane("XY").add(cq.Solid.makeSphere(r, b)))
        s = c if s is None else s.union(c)
    return s


def wires():
    """the four wires from the pads (x 12-32) under the board up the funnel into the 2 x 2 bundle, and the coax"""
    out = None
    off = WIRE_D / 2
    bundle = [(BUNDLE_Y - off, BUNDLE_Z - off), (BUNDLE_Y + off, BUNDLE_Z - off),
              (BUNDLE_Y - off, BUNDLE_Z + off), (BUNDLE_Y + off, BUNDLE_Z + off)]
    zu = Z0 + WIRE_D / 2 + 0.05
    for k, y in enumerate(WIRE_Y):
        by, bz = bundle[k]
        w = _rod([(12.0 + 6 * k, y, zu), (1.0, y, zu), (-PLUG_IN, by, bz), (X_NOZ - 15, by, bz)], WIRE_D / 2 - 0.05)
        out = w if out is None else out.union(w)
    # the coax in the bundle's top groove, up the chimney and over the board to the module
    gz = BUNDLE_Z + 1 + math.sqrt((1 + COAX_D / 2) ** 2 - 1)
    c = _rod([(X_NOZ - 15, BUNDLE_Y, gz), (-PLUG_IN, BUNDLE_Y, gz), (0.6, COAX_Y, COAX_Z), (45.0, COAX_Y, COAX_Z)],
             COAX_D / 2 - 0.02)
    return out.union(c)


if __name__ == "__main__":
    import os
    out = os.path.dirname(os.path.abspath(__file__))
    for f in ("tpu_sleeve_plug_coax.stl", "tpu_sleeve_plug_wires.stl", "tpu_sleeve_plug.stl"):   # v3's plugs; the plug alone
        if os.path.exists(os.path.join(out, f)):
            os.remove(os.path.join(out, f))
    T, P, G = tube(), plug(), grommet()

    def flat(s, axis_sign):                    # stand on an x face
        s = s.rotate((0, 0, 0), (0, 1, 0), axis_sign * 90)
        return s.translate((0, 0, -s.val().BoundingBox().zmin))
    cq.exporters.export(flat(T, -1), os.path.join(out, "tpu_sleeve.stl"), tolerance=0.02, angularTolerance=0.1)
    cq.exporters.export(flat(G, 1), os.path.join(out, "tpu_sleeve_grommet.stl"), tolerance=0.02, angularTolerance=0.1)
    # one print job: the plug and the grommet side by side (the owner: "kan tyllen ikke printes i samme print?")
    Pf, Gf = flat(P, -1), flat(G, 1)
    pb, gb = Pf.val().BoundingBox(), Gf.val().BoundingBox()
    both = Pf.union(Gf.translate((pb.xmin - gb.xmin + (pb.xlen - gb.xlen) / 2, pb.ymax - gb.ymin + 8, 0)))
    cq.exporters.export(both, os.path.join(out, "tpu_sleeve_plug_and_grommet.stl"), tolerance=0.02, angularTolerance=0.1)
    # everything in one job: the tube with the plug and the grommet beside it (the owner, 2026-11-26: one print, one start)
    Tf = flat(T, -1)
    tb, bb = Tf.val().BoundingBox(), both.val().BoundingBox()
    every = Tf.union(both.translate((tb.xmin - bb.xmin, tb.ymax - bb.ymin + 10, 0)))
    eb = every.val().BoundingBox()
    print("all three: %.0f x %.0f mm, %.0f mm high" % (eb.xlen, eb.ylen, eb.zlen))
    cq.exporters.export(every, os.path.join(out, "tpu_sleeve_all.stl"), tolerance=0.02, angularTolerance=0.1)
    B, W = board(), wires()
    ov = lambda a, b: round(a.intersect(b).val().Volume(), 2) if a.intersect(b).solids().vals() else 0
    for name, s in (("tube", T), ("plug", P), ("grommet", G)):
        print(name, "valid", s.val().isValid(), "solids", len(s.solids().vals()))
    print("board x tube", ov(B, T), "| board x plug", ov(B, P), "| wires x plug", ov(W, P), "| wires x board", ov(W, B),
          "| wires x tube", ov(W, T), "| wires x grommet (the grip)", ov(W, G), "| grommet x plug (the wedge)", ov(G, P),
          "| plug x tube (the squeeze)", ov(P, T))
    print("bundle %.2f, bore %.1f; grommet %.2f -> %.2f over %.0f mm, collar %.1f; seat %.2f -> %.2f; block %.1f thick, "
          "%.1f mm more than v2's cap; seat wall at the bottom %.2f"
          % (BUNDLE_D, BORE, 2 * r_g(0), 2 * r_g(G_LEN), G_LEN, 2 * (r_g(G_LEN) + COLLAR_W), 2 * SEAT_R0,
             2 * (SEAT_R0 + SEAT_LEN * math.tan(TAPER)), CAP_T + X_CAP - X_NOZ, X_CAP - X_NOZ, NOZZLE_WALL))
