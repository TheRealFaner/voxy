struct Frustum {
    vec4 planes[6];
};

bool testPlane(vec4 plane, vec3 base, float size) {
    return dot(plane.xyz, base+mix(vec3(size), vec3(0), lessThan(plane.xyz, vec3(0)))) >= -plane.w;
}

//TODO: optimize this, this can be done by computing the base point value, then multiplying and adding a seperate value by the size
bool outsideFrustum(in Frustum frustum, vec3 pos, float size) {
    // Plane 5 is the far plane on the main frame (harmless to test) and the portal clip plane on
    // portal passes, written by the GL46 backend after frustum update (PORTAL_VIEW_API.md §4.6).
    return !(testPlane(frustum.planes[0], pos, size) && testPlane(frustum.planes[1], pos, size) &&
           testPlane(frustum.planes[2], pos, size) && testPlane(frustum.planes[3], pos, size) &&
           testPlane(frustum.planes[4], pos, size) && testPlane(frustum.planes[5], pos, size));
}